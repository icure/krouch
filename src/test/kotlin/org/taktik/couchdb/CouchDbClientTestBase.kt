package org.taktik.couchdb

import java.util.UUID

abstract class CouchDbClientTestBase {
	protected val log = org.slf4j.LoggerFactory.getLogger(this::class.java)

	protected val databaseHost =  System.getProperty("krouch.test.couchdb.server.url", "http://localhost:5984")
	protected val defaultTestDatabaseName =  System.getProperty("krouch.test.couchdb.database.name", "krouch-test")
	protected val userName = System.getProperty("krouch.test.couchdb.username", "admin")
	protected val password = System.getProperty("krouch.test.couchdb.password", "password")

	protected fun clientForDb(db: String): Client = TODO()
	protected fun clientForRandomDb(): Client = clientForDb("krouch-test-${UUID.randomUUID()}")
	protected val defaultClient: Client = clientForDb(defaultTestDatabaseName)
}