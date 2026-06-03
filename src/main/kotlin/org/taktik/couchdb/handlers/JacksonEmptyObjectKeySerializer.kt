package org.taktik.couchdb.handlers

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import org.taktik.couchdb.entity.EmptyObjectKey

class JacksonEmptyObjectKeySerializer : JsonSerializer<EmptyObjectKey>() {
    override fun serialize(value: EmptyObjectKey, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeStartObject()
        gen.writeEndObject()
    }
}