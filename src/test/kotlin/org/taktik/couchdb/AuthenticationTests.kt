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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbJwtSecret
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbPassword
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUrl
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUsername
import org.taktik.couchdb.impl.ClientImpl
import org.taktik.couchdb.impl.auth.CouchDbAuthentication
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end checks of every [CouchDbAuthentication] flavour against the test CouchDB: each one must let
 * [ClientImpl] read and write, and bad credentials must be rejected by the server. JWT support is enabled on the
 * test container by [DockerSetupListener].
 */
@ExperimentalCoroutinesApi
class AuthenticationTests {
	private val httpClient = NettyWebClient()
	private val dbName = "krouch-test-authentication"
	private val adminClient = clientWith { CouchDbAuthentication.Basic(couchDbUsername, couchDbPassword) }

	private fun clientWith(credentialsProvider: suspend () -> CouchDbAuthentication) = ClientImpl(
		httpClient = httpClient,
		couchDBUri = URI(couchDbUrl),
		dbName = dbName,
		credentialsProvider = credentialsProvider,
		strictMode = true
	)

	@BeforeEach
	fun setupDatabase() = runBlocking {
		if (!adminClient.exists()) adminClient.create(8, 2)
	}

	@Test
	fun basicAuthenticationCanReadAndWrite() = runBlocking {
		assertCanReadAndWrite(clientWith { CouchDbAuthentication.Basic(couchDbUsername, couchDbPassword) })
	}

	@Test
	fun usernamePasswordConstructorUsesBasicAuthentication() = runBlocking {
		assertCanReadAndWrite(
			ClientImpl(
				httpClient = httpClient,
				couchDBUri = URI(couchDbUrl),
				dbName = dbName,
				username = couchDbUsername,
				password = couchDbPassword,
				strictMode = true
			)
		)
	}

	@Test
	fun basicAuthenticationWithWrongPasswordIsRejected() = runBlocking {
		assertIsRejected(clientWith { CouchDbAuthentication.Basic(couchDbUsername, "not-the-password") })
	}

	@Test
	fun sessionAuthenticationCanReadAndWrite() = runBlocking {
		val sessionId = openSession()
		assertCanReadAndWrite(clientWith { CouchDbAuthentication.Session(sessionId) })
	}

	@Test
	fun sessionAuthenticationWithUnknownSessionIsRejected() = runBlocking {
		assertIsRejected(clientWith { CouchDbAuthentication.Session("not-a-real-session") })
	}

	@Test
	fun jwtAuthenticationCanReadAndWrite() = runBlocking {
		val jwt = signJwt(couchDbJwtSecret)
		assertCanReadAndWrite(clientWith { CouchDbAuthentication.Jwt(jwt) })
	}

	@Test
	fun jwtSignedWithWrongKeyIsRejected() = runBlocking {
		assertIsRejected(clientWith { CouchDbAuthentication.Jwt(signJwt("some-other-secret".toByteArray())) })
	}

	@Test
	fun credentialsProviderIsInvokedForEveryRequest() = runBlocking {
		val calls = AtomicInteger(0)
		val sessionId = openSession()
		val jwt = signJwt(couchDbJwtSecret)
		// Rotate between authentication methods on each call: every request must pick up the current value.
		val client = clientWith {
			when (calls.getAndIncrement() % 3) {
				0 -> CouchDbAuthentication.Basic(couchDbUsername, couchDbPassword)
				1 -> CouchDbAuthentication.Session(sessionId)
				else -> CouchDbAuthentication.Jwt(jwt)
			}
		}
		repeat(6) { assertTrue(client.exists()) }
		assertEquals(6, calls.get())
	}

	private suspend fun assertCanReadAndWrite(client: Client) {
		assertTrue(client.exists())
		val id = UUID.randomUUID().toString()
		val created = client.create(RevisionedDoc(id = id, value = "authenticated"), RevisionedDoc::class.java)
		val fetched = client.get(id, RevisionedDoc::class.java)
		assertEquals(created.rev, fetched?.rev)
		assertEquals("authenticated", fetched?.value)
	}

	/** CouchDB answers 401 to bad credentials: whatever the client surfaces, the write must not have happened. */
	private suspend fun assertIsRejected(client: Client) {
		val id = UUID.randomUUID().toString()
		runCatching { client.create(RevisionedDoc(id = id, value = "should not be written"), RevisionedDoc::class.java) }
		assertNull(adminClient.get(id, RevisionedDoc::class.java))
	}

	/** Opens a cookie session for the admin user via `POST /_session` and returns the `AuthSession` cookie value. */
	private fun openSession(): String {
		val form = "name=${URLEncoder.encode(couchDbUsername, StandardCharsets.UTF_8)}&password=${URLEncoder.encode(couchDbPassword, StandardCharsets.UTF_8)}"
		val response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("$couchDbUrl/_session"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form))
				.build(),
			HttpResponse.BodyHandlers.ofString()
		)
		assertEquals(200, response.statusCode(), response.body())
		return response.headers().allValues("Set-Cookie")
			.firstNotNullOf { Regex("^AuthSession=([^;]+)").find(it)?.groupValues?.get(1) }
	}

	/** An HS256 JWT for the admin user, valid for an hour. */
	private fun signJwt(secret: ByteArray): String {
		val encoder = Base64.getUrlEncoder().withoutPadding()
		val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
		val exp = System.currentTimeMillis() / 1000 + 3600
		val payload = encoder.encodeToString("""{"sub":"$couchDbUsername","exp":$exp,"_couchdb.roles":["_admin"]}""".toByteArray())
		val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
		val signature = encoder.encodeToString(mac.doFinal("$header.$payload".toByteArray()))
		return "$header.$payload.$signature"
	}
}
