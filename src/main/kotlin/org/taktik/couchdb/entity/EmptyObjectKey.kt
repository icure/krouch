package org.taktik.couchdb.entity

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.taktik.couchdb.handlers.JacksonEmptyObjectKeySerializer
import org.taktik.couchdb.handlers.JacksonEmptyObjectKeyDeserializer

/**
 * Serializes as an empty object
 */
@JsonSerialize(using = JacksonEmptyObjectKeySerializer::class)
@JsonDeserialize(using = JacksonEmptyObjectKeyDeserializer::class)
object EmptyObjectKey