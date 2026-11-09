package org.taktik.couchdb

import io.icure.asyncjacksonhttpclient.netty.NettyWebClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbPassword
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUrl
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUsername
import org.taktik.couchdb.entity.DatabaseInfo
import org.taktik.couchdb.entity.DesignDocument
import org.taktik.couchdb.entity.View
import org.taktik.couchdb.entity.ViewQuery
import org.taktik.couchdb.exception.CouchDbException
import java.net.URI
import java.util.UUID

/**
 * [Client.compactDatabase], [Client.compact] and [Client.viewCleanup].
 */
class CompactionAndCleanupTests {
	private val httpClient = NettyWebClient()
	private lateinit var dbName: String
	private lateinit var client: Client

	private fun clientFor(dbName: String) =
		ClientImpl(httpClient, URI(couchDbUrl), dbName, couchDbUsername, couchDbPassword, strictMode = true)

	@BeforeEach
	fun setUp() = runBlocking {
		dbName = "krouch-test-compaction-${UUID.randomUUID()}"
		client = clientFor(dbName)
		check(client.create(1, 1)) { "Could not create test database" }
	}

	@AfterEach
	fun tearDown() {
		runBlocking { client.destroyDatabase() }
	}

	private suspend fun dbInfo(): DatabaseInfo =
		client.databaseInfos(flowOf(dbName)).single().info ?: error("No info for $dbName")

	private suspend fun awaitDatabaseCompactionEnd() {
		val deadline = System.currentTimeMillis() + 30_000
		while (dbInfo().compactRunning == true) {
			check(System.currentTimeMillis() < deadline) { "Database compaction did not finish within 30s" }
			delay(200)
		}
	}

	private suspend fun createDesignDocument(name: String, vararg viewNames: String) {
		client.create(
			DesignDocument(
				id = "_design/$name",
				views = viewNames.associateWith { View(map = "function(doc) { if (doc.value) emit(doc.value, null) }") }
			)
		)
	}

	private suspend fun queryAllRows(designDocName: String, viewName: String) =
		client.queryViewNoValue<String>(
			ViewQuery().designDocId("_design/$designDocName").viewName(viewName).includeDocs(false)
		).toList()

	@Test
	fun compactDatabaseReturnsTrueAndReclaimsSpaceFromOldRevisions() = runBlocking {
		var doc = client.create(RevisionedDoc(id = "doc", value = "v0"))
		repeat(300) { i -> doc = client.update(doc.copy(value = "v${i + 1}".padEnd(1024, 'x'))) }
		awaitDatabaseCompactionEnd()
		val fileSizeBefore = dbInfo().sizes.file

		assertTrue(client.compactDatabase())
		awaitDatabaseCompactionEnd()

		val infoAfter = dbInfo()
		assertTrue(
			infoAfter.sizes.file < fileSizeBefore,
			"file size must shrink after compaction (before=$fileSizeBefore, after=${infoAfter.sizes.file})"
		)
		assertEquals(1L, infoAfter.docCount)
		assertEquals(doc.rev, client.get<RevisionedDoc>("doc")?.rev, "the latest revision must survive compaction")
	}

	@Test
	fun compactDatabaseOnMissingDatabaseThrowsNotFound() = runBlocking {
		val missing = clientFor("krouch-test-compaction-missing-${UUID.randomUUID()}")
		val ex = assertThrows<CouchDbException> { missing.compactDatabase() }
		assertEquals(404, ex.statusCode)
	}

	@Test
	fun compactDesignDocumentReturnsTrueAndKeepsViewUsable() = runBlocking {
		createDesignDocument("Test", "by_value")
		repeat(20) { client.create(RevisionedDoc(id = "doc-$it", value = "value-$it")) }
		assertEquals(20, queryAllRows("Test", "by_value").size, "the view must be built before compaction")

		assertTrue(client.compact("Test"))

		assertEquals(20, queryAllRows("Test", "by_value").size, "the view must still be queryable after compaction")
	}

	@Test
	fun compactAcceptsDesignDocumentIdWithDesignPrefix() = runBlocking {
		createDesignDocument("Test", "by_value")
		repeat(5) { client.create(RevisionedDoc(id = "doc-$it", value = "value-$it")) }
		assertEquals(5, queryAllRows("Test", "by_value").size)

		assertTrue(client.compact("_design/Test"))
	}

	@Test
	fun compactMissingDesignDocumentThrowsNotFound() = runBlocking {
		val ex = assertThrows<CouchDbException> { client.compact("DoesNotExist") }
		assertEquals(404, ex.statusCode)
	}

	@Test
	fun viewCleanupReturnsTrueOnDatabaseWithoutViews() = runBlocking {
		assertTrue(client.viewCleanup())
	}

	@Test
	fun viewCleanupReturnsTrueAfterViewsChangeAndKeepsCurrentIndexesUsable() = runBlocking {
		createDesignDocument("Kept", "by_value")
		createDesignDocument("Dropped", "by_value")
		repeat(10) { client.create(RevisionedDoc(id = "doc-$it", value = "value-$it")) }
		assertEquals(10, queryAllRows("Kept", "by_value").size)
		assertEquals(10, queryAllRows("Dropped", "by_value").size)
		// Leave a stale index on disk by deleting the design document that built it
		client.delete(client.get<DesignDocument>("_design/Dropped")!!)

		assertTrue(client.viewCleanup())

		assertEquals(10, queryAllRows("Kept", "by_value").size, "indexes of live design documents must survive cleanup")
	}

	@Test
	fun viewCleanupOnMissingDatabaseThrowsNotFound() = runBlocking {
		val missing = clientFor("krouch-test-compaction-missing-${UUID.randomUUID()}")
		val ex = assertThrows<CouchDbException> { missing.viewCleanup() }
		assertEquals(404, ex.statusCode)
	}
}
