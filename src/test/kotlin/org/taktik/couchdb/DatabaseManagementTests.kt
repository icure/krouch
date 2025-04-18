package org.taktik.couchdb

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DatabaseManagementTests : CouchDbClientTestBase() {
	@Test
	fun createAndExistTest(): Unit = runBlocking {
		val client = clientForRandomDb()
		assertFalse(client.exists())
		assertTrue(client.create(q = null, n = null))
		assertTrue(client.exists())
	}
}