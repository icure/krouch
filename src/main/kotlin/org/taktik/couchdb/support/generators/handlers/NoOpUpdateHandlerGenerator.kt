package org.taktik.couchdb.support.generators.handlers

class NoOpUpdateHandlerGenerator<T : Any> : UpdateHandlerGenerator<T> {
	override fun generateUpdateHandlerFunctions(metaDataSource: T): Map<String, String> = emptyMap()
}