package org.taktik.couchdb.handlers

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import org.taktik.couchdb.entity.View

class ViewSerializer : JsonSerializer<View>() {
	override fun serialize(value: View, gen: JsonGenerator, serializers: SerializerProvider) {
		gen.writeStartObject()
		gen.writeStringField("map", value.map.replace("^map\\s*=\\s*".toRegex(), ""))
		value.reduce?.let { gen.writeStringField("reduce", it.replace("^reduce\\s*=\\s*".toRegex(), "")) }
		gen.writeEndObject()
	}
}