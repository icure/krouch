package org.taktik.couchdb.handlers

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import org.taktik.couchdb.entity.DesignDocument

class DesignDocumentSerializer : JsonSerializer<DesignDocument>() {
	override fun serialize(value: DesignDocument, gen: JsonGenerator, serializers: SerializerProvider) {
		gen.writeStartObject()

		gen.writeStringField("_id", value.id)
		value.rev?.let { gen.writeStringField("_rev", it) }
		value.language?.let { gen.writeStringField("language", it) }

		gen.writeFieldName("views")
		gen.writeStartObject()
		value.views.forEach { (key, view) ->
			gen.writeFieldName(key)
			serializers.defaultSerializeValue(view, gen)
		}
		if (value.lib.isNotEmpty()) {
			gen.writeFieldName(DesignDocument.LIB_VIEW_KEY)
			serializers.defaultSerializeValue(value.lib, gen)
		}
		gen.writeEndObject()

		gen.writeFieldName("lists")
		serializers.defaultSerializeValue(value.lists, gen)

		gen.writeFieldName("shows")
		serializers.defaultSerializeValue(value.shows, gen)

		value.updateHandlers?.let {
			gen.writeFieldName("updateHandlers")
			serializers.defaultSerializeValue(it, gen)
		}

		gen.writeFieldName("filters")
		serializers.defaultSerializeValue(value.filters, gen)

		gen.writeEndObject()
	}
}
