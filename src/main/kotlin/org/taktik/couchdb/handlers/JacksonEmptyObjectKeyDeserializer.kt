package org.taktik.couchdb.handlers

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import org.taktik.couchdb.entity.EmptyObjectKey

class JacksonEmptyObjectKeyDeserializer : JsonDeserializer<EmptyObjectKey>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): EmptyObjectKey {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            throw ctxt.wrongTokenException(p, EmptyObjectKey::class.java, JsonToken.START_OBJECT, "Expected empty object")
        }
        if (p.nextToken() != JsonToken.END_OBJECT) {
            ctxt.reportInputMismatch<EmptyObjectKey>(EmptyObjectKey::class.java, "Expected empty object but got non-empty object for EmptyObjectKey")
        }
        return EmptyObjectKey
    }
}