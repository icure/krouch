package org.taktik.couchdb.handlers

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.node.ObjectNode
import org.taktik.couchdb.entity.DesignDocument
import org.taktik.couchdb.entity.View

class DesignDocumentDeserializer : JsonDeserializer<DesignDocument>() {
	override fun deserialize(p: JsonParser, ctxt: DeserializationContext): DesignDocument {
		val node = p.readValueAsTree<ObjectNode>()

		val id = node.get("_id")?.asText() ?: throw ctxt.instantiationException(DesignDocument::class.java, "Missing required field _id")
		val rev = node.get("_rev")?.asText()
		val language = node.get("language")?.asText()

		val views = mutableMapOf<String, View>()
		val lib = mutableMapOf<String, String>()

		val viewsNode = node.get("views") as? ObjectNode
		viewsNode?.properties()?.forEach { (key, viewNode) ->
			if (key == DesignDocument.LIB_VIEW_KEY) {
				(viewNode as? ObjectNode)?.properties()?.forEach { (k, v) ->
					lib[k] = v.asText()
				}
			} else {
				views[key] = p.codec.treeToValue(viewNode, View::class.java)
			}
		}

		@Suppress("UNCHECKED_CAST")
		val lists = node.get("lists")?.let { p.codec.treeToValue(it, Map::class.java) as Map<String, String> } ?: emptyMap()
		@Suppress("UNCHECKED_CAST")
		val shows = node.get("shows")?.let { p.codec.treeToValue(it, Map::class.java) as Map<String, String> } ?: emptyMap()
		@Suppress("UNCHECKED_CAST")
		val updateHandlers = node.get("updateHandlers")?.let { p.codec.treeToValue(it, Map::class.java) as Map<String, String> }
		@Suppress("UNCHECKED_CAST")
		val filters = node.get("filters")?.let { p.codec.treeToValue(it, Map::class.java) as Map<String, String> } ?: emptyMap()

		return DesignDocument(
			id = id,
			rev = rev,
			language = language,
			views = views,
			lib = lib,
			lists = lists,
			shows = shows,
			updateHandlers = updateHandlers,
			filters = filters,
		)
	}
}
