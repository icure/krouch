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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbPassword
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUrl
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUsername
import org.taktik.couchdb.entity.ChangeRow
import org.taktik.couchdb.entity.ChangesStyle
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID

/**
 * [Client.getChangesPage]: the unfiltered, document-less `_changes` feed - the only CouchDB endpoint that
 * enumerates deleted documents (tombstones), which `_all_docs` never lists.
 */
@ExperimentalCoroutinesApi
class GetChangesPageTests {
	private val httpClient = NettyWebClient()
	private val dbName = "krouch-test-changes-page"
	private val client = ClientImpl(httpClient, URI(couchDbUrl), dbName, couchDbUsername, couchDbPassword, strictMode = true)

	@BeforeEach
	fun setupDatabase() = runBlocking {
		if (!client.exists()) client.create(8, 2)
	}

	/** Every row of the feed from the beginning, plus the final `last_seq`. */
	private suspend fun readAllRows(limit: Int = 50): Pair<List<ChangeRow>, String> {
		val rows = mutableListOf<ChangeRow>()
		var since = "0"
		while (true) {
			val page = client.getChangesPage(since, limit)
			if (page.results.isEmpty()) return rows to page.lastSeq
			rows += page.results
			since = page.lastSeq
		}
	}

	@Test
	fun testDeletedDocumentAppearsAsTombstoneRow() = runBlocking {
		val id = UUID.randomUUID().toString()
		val created = client.create(RevisionedDoc(id = id, value = "v1"), RevisionedDoc::class.java)
		val tombstone = client.delete(created)

		val (rows, _) = readAllRows()

		val row = rows.single { it.id == id }
		assertTrue(row.deleted, "a deleted document must be flagged deleted")
		assertEquals(listOf(tombstone.rev), row.revs, "the row must carry the tombstone rev")
	}

	@Test
	fun testStyleAllDocsListsEveryLeafRevision() = runBlocking {
		val id = UUID.randomUUID().toString()
		val created = client.create(RevisionedDoc(id = id, value = "root"), RevisionedDoc::class.java)
		val parentGeneration = checkNotNull(created.rev).substringBefore("-").toInt()
		val parentHash = created.rev!!.substringAfter("-")
		val leafA = RevisionedDoc(id, "${parentGeneration + 1}-aaaa1111aaaa1111aaaa1111aaaa1111", revisions = DocRevisions(parentGeneration + 1, listOf("aaaa1111aaaa1111aaaa1111aaaa1111", parentHash)), value = "A")
		val leafB = RevisionedDoc(id, "${parentGeneration + 1}-bbbb2222bbbb2222bbbb2222bbbb2222", revisions = DocRevisions(parentGeneration + 1, listOf("bbbb2222bbbb2222bbbb2222bbbb2222", parentHash)), value = "B")
		client.bulkImportWithoutNewEdits(listOf(leafA, leafB), RevisionedDoc::class.java).toList()

		val (rows, _) = readAllRows()

		val row = rows.single { it.id == id }
		assertFalse(row.deleted)
		assertEquals(setOf(leafA.rev, leafB.rev), row.revs.toSet(), "style=all_docs must list both conflicting leaves")
	}

	@Test
	fun testMainOnlyStyleListsTheWinningLeafAlone() = runBlocking {
		val id = UUID.randomUUID().toString()
		val created = client.create(RevisionedDoc(id = id, value = "root"), RevisionedDoc::class.java)
		val parentGeneration = checkNotNull(created.rev).substringBefore("-").toInt()
		val parentHash = created.rev!!.substringAfter("-")
		val leafA = RevisionedDoc(id, "${parentGeneration + 1}-aaaa1111aaaa1111aaaa1111aaaa1111", revisions = DocRevisions(parentGeneration + 1, listOf("aaaa1111aaaa1111aaaa1111aaaa1111", parentHash)), value = "A")
		val leafB = RevisionedDoc(id, "${parentGeneration + 1}-bbbb2222bbbb2222bbbb2222bbbb2222", revisions = DocRevisions(parentGeneration + 1, listOf("bbbb2222bbbb2222bbbb2222bbbb2222", parentHash)), value = "B")
		client.bulkImportWithoutNewEdits(listOf(leafA, leafB), RevisionedDoc::class.java).toList()

		var since = "0"
		var row: ChangeRow? = null
		while (row == null) {
			val page = client.getChangesPage(since, 50, ChangesStyle.MAIN_ONLY)
			if (page.results.isEmpty()) break
			row = page.results.firstOrNull { it.id == id }
			since = page.lastSeq
		}

		assertEquals(listOf(leafB.rev), checkNotNull(row).revs, "main_only lists only the winning leaf (highest hash on equal generation)")
	}

	@Test
	fun testLimitPagesThroughTheFeedWithoutRepeatingRows() = runBlocking {
		repeat(5) { client.create(RevisionedDoc(id = UUID.randomUUID().toString(), value = "p$it"), RevisionedDoc::class.java) }

		val first = client.getChangesPage("0", limit = 2)
		assertEquals(2, first.results.size)
		assertNotNull(first.pending)
		val second = client.getChangesPage(first.lastSeq, limit = 2)
		assertEquals(2, second.results.size)
		assertTrue(first.results.map { it.id }.intersect(second.results.map { it.id }.toSet()).isEmpty(), "pages must not overlap")
		assertTrue(second.results.all { it.seq.isNotBlank() })
	}

	@Test
	fun testPageSinceTheEndIsEmpty() = runBlocking {
		client.create(RevisionedDoc(id = UUID.randomUUID().toString(), value = "x"), RevisionedDoc::class.java)
		val (_, lastSeq) = readAllRows()

		val page = client.getChangesPage(lastSeq, limit = 50)

		assertTrue(page.results.isEmpty())
		assertEquals(0L, page.pending)
	}

	/**
	 * Pins the property the archival tool's "still available older revisions" feature relies on: an explicit
	 * (id, rev) pair fetches a *non-leaf* revision's body as long as compaction hasn't removed it, and yields
	 * nothing afterwards.
	 */
	@Test
	fun testBulkGetWithExplicitOlderRevReturnsItsBodyUntilCompaction() = runBlocking {
		val id = UUID.randomUUID().toString()
		val v1 = client.create(RevisionedDoc(id = id, value = "v1"), RevisionedDoc::class.java)
		val v2 = client.update(v1.copy(value = "v2"), RevisionedDoc::class.java)
		val rev1 = checkNotNull(v1.rev)
		val rev2 = checkNotNull(v2.rev)

		val before = client.getBulkByIdsAndRevs(listOf(id to rev1), RevisionedDoc::class.java)
		assertEquals(listOf("v1"), before.map { it.value }, "the older revision's body must still be readable")
		assertEquals(rev1, before.single().rev)
		assertNotNull(before.single().revisions, "revs=true must embed the ancestor chain")

		compactAndWait()

		val after = client.getBulkByIdsAndRevs(listOf(id to rev1, id to rev2), RevisionedDoc::class.java)
		assertEquals(listOf("v2"), after.map { it.value }, "only the leaf survives compaction; the missing older rev is skipped")
	}

	private suspend fun compactAndWait() {
		val http = HttpClient.newHttpClient()
		val auth = "Basic " + Base64.getEncoder().encodeToString("$couchDbUsername:$couchDbPassword".toByteArray())
		val compact = HttpRequest.newBuilder(URI.create("$couchDbUrl/$dbName/_compact"))
			.header("Authorization", auth).header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.noBody()).build()
		assertEquals(202, http.send(compact, HttpResponse.BodyHandlers.ofString()).statusCode())
		val info = HttpRequest.newBuilder(URI.create("$couchDbUrl/$dbName")).header("Authorization", auth).GET().build()
		val deadline = System.currentTimeMillis() + 30_000
		while (System.currentTimeMillis() < deadline) {
			delay(200)
			if (!http.send(info, HttpResponse.BodyHandlers.ofString()).body().contains("\"compact_running\":true")) return
		}
		error("compaction of $dbName did not finish within 30s")
	}
}
