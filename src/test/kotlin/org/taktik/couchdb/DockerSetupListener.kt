package org.taktik.couchdb

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
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
		awaitReady()
	}

	/**
	 * `docker run -d` only waits for the container to start, not for CouchDB inside it to actually accept
	 * HTTP connections (Erlang VM boot + first-time database setup takes several seconds). Without this,
	 * whichever test class happens to run first races the container and gets connection-reset/refused errors
	 * instead of a real test failure.
	 */
	private fun awaitReady() {
		val client = HttpClient.newHttpClient()
		val request = HttpRequest.newBuilder(URI.create(couchDbUrl)).timeout(Duration.ofSeconds(2)).GET().build()
		val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60)
		while (System.currentTimeMillis() < deadline) {
			try {
				if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return
			} catch (_: Exception) {
				// Not up yet - fall through and retry.
			}
			Thread.sleep(500)
		}
		error("CouchDB test container did not become ready at $couchDbUrl within 60s")
	}
}