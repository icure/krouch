package org.taktik.couchdb.entity

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.net.URI

abstract class ViewQueryLike {
	abstract val keys: List<Any?>?
	abstract val skipIfViewDoesNotExist: Boolean
	abstract val isIncludeDocs: Boolean
	abstract val ignoreNotFound: Boolean
	abstract fun buildQuery(): URI

	fun hasMultipleKeys(): Boolean = keys != null
	fun keysAsJson() = keys?.let { keys ->
		DEFAULT_MAPPER.writeValueAsString(
			DEFAULT_MAPPER.createObjectNode().apply {
				putArray("keys").apply {
					keys.forEach { addPOJO(it) }
				} }
		)
	} ?: "{\"keys\":[]}"

	companion object {
		internal val DEFAULT_MAPPER = ObjectMapper().apply {
			configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
			configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
		}
	}
}
