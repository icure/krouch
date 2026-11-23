package org.taktik.couchdb

import io.icure.asyncjacksonhttpclient.netty.NettyWebClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbPassword
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUrl
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUsername
import org.taktik.couchdb.entity.DesignDocument
import org.taktik.couchdb.entity.View
import org.taktik.couchdb.entity.ViewQuery
import org.taktik.couchdb.exception.CouchDbException
import java.net.URI
import java.util.UUID

/**
 * [Client.designDocInfo].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesignDocInfoTests {
	private val httpClient = NettyWebClient()
	private lateinit var client: Client

	private fun clientFor(dbName: String) =
		ClientImpl(httpClient, URI(couchDbUrl), dbName, couchDbUsername, couchDbPassword, strictMode = true)

	@BeforeEach
	fun setUp() = runBlocking {
		client = clientFor("krouch-test-ddoc-info-${UUID.randomUUID()}")
		check(client.create(1, 1)) { "Could not create test database" }
	}

	@AfterEach
	fun tearDown() {
		runBlocking { client.destroyDatabase() }
	}

	private suspend fun createDesignDocument(name: String, mapFunction: String = "function(doc) { if (doc.value) emit(doc.value, null) }") {
		client.create(DesignDocument(id = "_design/$name", views = mapOf("by_value" to View(map = mapFunction))))
	}

	private suspend fun queryAllRows(designDocName: String) =
		client.queryViewNoValue<String>(
			ViewQuery().designDocId("_design/$designDocName").viewName("by_value").includeDocs(false)
		).toList()

	@Test
	fun designDocInfoReturnsInfoOfFreshDesignDocument() = runBlocking {
		createDesignDocument("Test")

		// Note: _info itself triggers a background index build, so updater_running / waiting_* flags are racy
		// and intentionally not asserted here.
		val info = client.designDocInfo("Test")

		assertNotNull(info)
		assertEquals("Test", info!!.name)
		with(info.viewIndex) {
			assertEquals("javascript", language)
			assertTrue(signature.isNotBlank())
			assertFalse(compactRunning)
			assertEquals(0L, sizes.active, "nothing is indexed yet")
		}
	}

	@Test
	fun designDocInfoReflectsBuiltIndexSize() = runBlocking {
		createDesignDocument("Test")
		val sizeBefore = client.designDocInfo("Test")!!.viewIndex.sizes
		repeat(50) { client.create(RevisionedDoc(id = "doc-$it", value = "value-$it")) }
		assertEquals(50, queryAllRows("Test").size, "the view must be built")

		val sizeAfter = client.designDocInfo("Test")!!.viewIndex.sizes

		assertTrue(sizeAfter.active > sizeBefore.active, "active size must grow once indexed (before=$sizeBefore, after=$sizeAfter)")
		assertTrue(sizeAfter.external > 0, "external size must be positive once indexed")
		assertTrue(sizeAfter.file > 0, "file size must be positive once indexed")
	}

	@Test
	fun designDocInfoAcceptsDesignDocumentIdWithDesignPrefix() = runBlocking {
		createDesignDocument("Test")

		val withoutPrefix = client.designDocInfo("Test")!!
		val withPrefix = client.designDocInfo("_design/Test")!!

		assertEquals("Test", withPrefix.name)
		assertEquals(withoutPrefix.viewIndex.signature, withPrefix.viewIndex.signature)
	}

	@Test
	fun designDocInfoSignatureChangesWithViewDefinition() = runBlocking {
		createDesignDocument("A")
		createDesignDocument("B", "function(doc) { emit(doc._id, null) }")

		assertNotEquals(
			client.designDocInfo("A")!!.viewIndex.signature,
			client.designDocInfo("B")!!.viewIndex.signature
		)
	}

	@Test
	fun designDocInfoOfMissingDesignDocumentReturnsNull() = runBlocking {
		assertNull(client.designDocInfo("DoesNotExist"))
	}

	@Test
	fun designDocInfoOnMissingDatabaseReturnsNullOrThrowsNotFound() = runBlocking {
		val missing = clientFor("krouch-test-ddoc-info-missing-${UUID.randomUUID()}")
		val result = runCatching { missing.designDocInfo("Test") }
		result.exceptionOrNull()?.let { assertEquals(404, (it as CouchDbException).statusCode) }
			?: assertNull(result.getOrNull())
	}
}
