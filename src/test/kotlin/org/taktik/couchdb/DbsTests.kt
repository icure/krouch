package org.taktik.couchdb

import io.icure.asyncjacksonhttpclient.netty.NettyWebClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbPassword
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUrl
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUsername
import java.net.URI
import java.util.UUID

/**
 * [Client.dbs]: shard map documents read from the node-local `_dbs` system database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DbsTests {
	private val httpClient = NettyWebClient()

	private fun clientFor(dbName: String) =
		ClientImpl(httpClient, URI(couchDbUrl), dbName, couchDbUsername, couchDbPassword, strictMode = true)

	private suspend fun createDatabase(q: Int): Pair<String, Client> {
		val dbName = "krouch-test-dbs-${UUID.randomUUID()}"
		val client = clientFor(dbName)
		check(client.create(q, 1)) { "Could not create $dbName" }
		return dbName to client
	}

	@Test
	fun testDbsReturnsTheShardMapOfAnExistingDatabase() = runBlocking {
		val (dbName, client) = createDatabase(q = 4)
		try {
			val info = client.dbs(listOf(dbName)).toList().single()

			assertEquals(dbName, info.id)
			assertNotNull(info.rev)
			assertEquals(4, info.byRange.size, "q=4 must produce 4 shard ranges")
			assertTrue(info.byRange.values.all { it.size == 1 }, "n=1 must place each range on a single node")
			assertEquals(info.byRange.keys, info.byNode.values.flatten().toSet(), "by_node and by_range must describe the same ranges")
			assertEquals(4, info.changeLog.size, "the changelog must have one 'add' entry per shard")
			assertTrue(info.changeLog.all { it.isArray && it[0].asText() == "add" })
			assertTrue(
				info.shardSuffix.map { it.toChar() }.joinToString("").matches(Regex("""\.\d+""")),
				"shard_suffix must decode to '.<creation timestamp>'"
			)
		} finally {
			client.destroyDatabase()
		}
	}

	@Test
	fun testDbsReturnsOneDocumentPerRequestedDatabaseInOrder() = runBlocking {
		val (firstName, first) = createDatabase(q = 2)
		val (secondName, second) = createDatabase(q = 8)
		try {
			val infos = first.dbs(listOf(secondName, firstName)).toList()

			assertEquals(listOf(secondName, firstName), infos.map { it.id })
			assertEquals(listOf(8, 2), infos.map { it.byRange.size })
		} finally {
			first.destroyDatabase()
			second.destroyDatabase()
		}
	}

	@Test
	fun testDbsCanBeQueriedForOtherDatabasesThanTheClientOne() = runBlocking {
		val (dbName, client) = createDatabase(q = 2)
		try {
			// The client's own database does not exist: the query targets the server, not the client database
			val info = clientFor("krouch-test-dbs-not-created-${UUID.randomUUID()}").dbs(listOf(dbName)).toList().single()
			assertEquals(dbName, info.id)
		} finally {
			client.destroyDatabase()
		}
	}

	@Test
	fun testDbsSkipsMissingAndDeletedDatabases() = runBlocking {
		val (existingName, existing) = createDatabase(q = 2)
		val (deletedName, deleted) = createDatabase(q = 2)
		deleted.destroyDatabase()
		try {
			val infos = existing.dbs(listOf("krouch-test-dbs-missing-${UUID.randomUUID()}", deletedName, existingName)).toList()

			assertEquals(listOf(existingName), infos.map { it.id })
		} finally {
			existing.destroyDatabase()
		}
	}

	@Test
	fun testDbsWithNoIdsReturnsNothing() = runBlocking {
		assertEquals(emptyList<Any>(), clientFor("krouch-test-dbs-unused").dbs(emptyList()).toList())
	}
}
