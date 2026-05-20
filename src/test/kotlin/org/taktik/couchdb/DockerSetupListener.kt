package org.taktik.couchdb

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import java.util.concurrent.TimeUnit

class DockerSetupListener : LauncherSessionListener {
	companion object {
		val couchDbPort: String = System.getProperty("krouch.test.couchdb.port", "5984")
		val couchDbUrl: String = System.getProperty("krouch.test.couchdb.server.url", "http://localhost:$couchDbPort")
		val couchDbUsername: String = System.getProperty("krouch.test.couchdb.username", "icure")
		val couchDbPassword: String = System.getProperty("krouch.test.couchdb.password", "icure")
		private const val CONTAINER_NAME = "couchdb-krouch-test"
		private const val COUCH_DB_VERSION = "3.4.2"
	}


	override fun launcherSessionOpened(session: LauncherSession) {
		ProcessBuilder("/usr/local/bin/docker container rm -fv $CONTAINER_NAME".split(" "))
			.inheritIO().start().waitFor(1, TimeUnit.MINUTES)
		ProcessBuilder("/usr/local/bin/docker run -p $couchDbPort:5984 -e COUCHDB_USER=$couchDbUsername -e COUCHDB_PASSWORD=$couchDbPassword --name $CONTAINER_NAME -d couchdb:$COUCH_DB_VERSION".split(" "))
			.inheritIO().start().waitFor(1, TimeUnit.MINUTES)
	}
}