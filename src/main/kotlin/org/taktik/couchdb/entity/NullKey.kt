package org.taktik.couchdb.entity

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.taktik.couchdb.handlers.JacksonNullKeySerializer

/**
 * A value that serializes to null and can be used in a query to specify that the value for a parameter in the query
 * should be null.
 */
@JsonSerialize(using = JacksonNullKeySerializer::class)
object NullKey