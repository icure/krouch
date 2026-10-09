package org.taktik.couchdb.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.icure.asyncjacksonhttpclient.net.web.Request
import io.icure.asyncjacksonhttpclient.net.web.WebClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.taktik.couchdb.HeaderHandler
import reactor.core.publisher.Mono

@OptIn(ExperimentalCoroutinesApi::class)
class BasicAuthClient(
	httpClient: WebClient,
	couchDBUri: java.net.URI,
	dbName: String,
	credentialsProvider: () -> Pair<String, String>,
	objectMapper: ObjectMapper = ObjectMapper().also { it.registerKotlinModule() },
	headerHandlers: Map<String, HeaderHandler> = mapOf(),
	timingHandler: ((timing: Long, headers: Map<String, List<String>>) -> Mono<Unit>)? = null,
	strictMode: Boolean = false,
) : AbstractJacksonClient(
	httpClient = httpClient,
	couchDBUri = couchDBUri,
	dbName = dbName,
	credentialsProvider = credentialsProvider,
	objectMapper = objectMapper,
	headerHandlers = headerHandlers,
	timingHandler = timingHandler,
	strictMode = strictMode,
) {

	/**
	 * Alternative constructor for client with constant username and password.
	 */
	constructor(
		httpClient: WebClient,
		couchDBUri: java.net.URI,
		dbName: String,
		username: String,
		password: String,
		objectMapper: ObjectMapper = ObjectMapper().also { it.registerKotlinModule() },
		headerHandlers: Map<String, HeaderHandler> = mapOf(),
		timingHandler: ((timing: Long, headers: Map<String, List<String>>) -> Mono<Unit>)? = null,
		strictMode: Boolean = false
	) : this(
		httpClient = httpClient,
		couchDBUri = couchDBUri,
		dbName = dbName,
		credentialsProvider = { username to password },
		objectMapper = objectMapper,
		headerHandlers = headerHandlers,
		timingHandler = timingHandler,
		strictMode = strictMode
	)

	override fun Request.authenticated(): Request {
		val (username, password) = credentialsProvider()
		return basicAuth(username, password)
	}

}