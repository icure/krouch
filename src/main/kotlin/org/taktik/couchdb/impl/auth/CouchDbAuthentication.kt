package org.taktik.couchdb.impl.auth

import io.icure.asyncjacksonhttpclient.net.web.Request
import kotlinx.coroutines.ExperimentalCoroutinesApi

sealed interface CouchDbAuthentication {

	@OptIn(ExperimentalCoroutinesApi::class)
	fun authenticate(request: Request): Request

	data class Basic(val username: String, val password: String) : CouchDbAuthentication {
		@OptIn(ExperimentalCoroutinesApi::class)
		override fun authenticate(request: Request): Request =
			request.basicAuth(username, password)
	}

	data class Session(val sessionId: String) : CouchDbAuthentication {
		@OptIn(ExperimentalCoroutinesApi::class)
		override fun authenticate(request: Request): Request =
			request.header("Cookie", "AuthSession=$sessionId")
	}

	data class Jwt(val jwt: String) : CouchDbAuthentication {
		@OptIn(ExperimentalCoroutinesApi::class)
		override fun authenticate(request: Request): Request =
			request.header("Authorization", "Bearer $jwt")
	}
}