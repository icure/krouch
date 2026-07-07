/*
 *    Copyright 2020 Taktik SA
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package org.taktik.couchdb

import io.icure.asyncjacksonhttpclient.netty.NettyWebClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbPassword
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUrl
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUsername
import org.taktik.couchdb.entity.Option
import java.net.URI
import java.util.UUID

@ExperimentalCoroutinesApi
class GetAllLeafRevisionsAndBulkImportTests {
	private val httpClient = NettyWebClient()
	private val sourceDbName = "krouch-test-leaf-revisions-source"
	private val targetDbName = "krouch-test-leaf-revisions-target"

	private val sourceClient = ClientImpl(httpClient, URI(couchDbUrl), sourceDbName, couchDbUsername, couchDbPassword, strictMode = true)
	private val targetClient = ClientImpl(httpClient, URI(couchDbUrl), targetDbName, couchDbUsername, couchDbPassword, strictMode = true)

	@BeforeEach
	fun setupDatabases() = runBlocking {
		if (!sourceClient.exists()) sourceClient.create(8, 2)
		if (!targetClient.exists()) targetClient.create(8, 2)
	}

	/**
	 * Forces a genuine stored conflict: a normal sequential PUT/update can never do this (it always rejects a
	 * stale `_rev` with a 409, see [CouchDbClientTests.testClientUpdateOutdated]) - the only way to get two
	 * sibling leaf revisions under the same parent is to inject them with `new_edits=false`, exactly as
	 * replication does. That's precisely what [Client.bulkImportWithoutNewEdits] is for.
	 */
	private suspend fun createConflictingLeaves(client: Client, id: String, parentRev: String): Pair<RevisionedDoc, RevisionedDoc> {
		val parentGeneration = parentRev.substringBefore("-").toInt()
		val parentHash = parentRev.substringAfter("-")
		val leafA = RevisionedDoc(
			id = id,
			rev = "${parentGeneration + 1}-aaaa1111aaaa1111aaaa1111aaaa1111",
			revisions = DocRevisions(parentGeneration + 1, listOf("aaaa1111aaaa1111aaaa1111aaaa1111", parentHash)),
			value = "A",
		)
		val leafB = RevisionedDoc(
			id = id,
			rev = "${parentGeneration + 1}-bbbb2222bbbb2222bbbb2222bbbb2222",
			revisions = DocRevisions(parentGeneration + 1, listOf("bbbb2222bbbb2222bbbb2222bbbb2222", parentHash)),
			value = "B",
		)
		val results = client.bulkImportWithoutNewEdits(listOf(leafA, leafB), RevisionedDoc::class.java).toList()
		results.forEach { assertTrue(it.ok == true, "bulkImportWithoutNewEdits failed for ${it.id}: ${it.error} ${it.reason}") }
		return leafA to leafB
	}

	@Test
	fun testGetAllLeafRevisionsReturnsBothOpenConflicts() = runBlocking {
		val id = UUID.randomUUID().toString()
		val created = sourceClient.create(RevisionedDoc(id = id, value = "root"), RevisionedDoc::class.java)
		val (leafA, leafB) = createConflictingLeaves(sourceClient, id, checkNotNull(created.rev))

		val leaves = sourceClient.getAllLeafRevisions(id, RevisionedDoc::class.java)

		assertEquals(2, leaves.size)
		assertEquals(setOf(leafA.rev, leafB.rev), leaves.map { it.rev }.toSet())
		assertEquals(setOf("A", "B"), leaves.map { it.value }.toSet())
		leaves.forEach { assertNotNull(it.revisions) }

		// Sanity-check that CouchDB itself considers this a real stored conflict, independent of our new method.
		@Suppress("DEPRECATION")
		val winning = checkNotNull(sourceClient.get(id, RevisionedDoc::class.java, Option.CONFLICTS))
		assertEquals(1, winning.conflicts?.size)
	}

	@Test
	fun testBulkImportWithoutNewEditsReplaysConflictIntoAnotherDatabase() = runBlocking {
		val id = UUID.randomUUID().toString()
		val created = sourceClient.create(RevisionedDoc(id = id, value = "root"), RevisionedDoc::class.java)
		createConflictingLeaves(sourceClient, id, checkNotNull(created.rev))

		// Full pipeline this new project relies on: read every leaf from the source, replay them verbatim
		// into an unrelated, previously-empty database.
		val leaves = sourceClient.getAllLeafRevisions(id, RevisionedDoc::class.java)
		val importResults = targetClient.bulkImportWithoutNewEdits(leaves, RevisionedDoc::class.java).toList()
		importResults.forEach { assertTrue(it.ok == true, "replay failed for ${it.id}: ${it.error} ${it.reason}") }

		val replayedLeaves = targetClient.getAllLeafRevisions(id, RevisionedDoc::class.java)
		assertEquals(leaves.map { it.rev }.toSet(), replayedLeaves.map { it.rev }.toSet())
		assertEquals(leaves.map { it.value }.toSet(), replayedLeaves.map { it.value }.toSet())

		@Suppress("DEPRECATION")
		val winning = checkNotNull(targetClient.get(id, RevisionedDoc::class.java, Option.CONFLICTS))
		assertEquals(1, winning.conflicts?.size)
	}
}
