package org.taktik.couchdb.support.generators.handlers

interface UpdateHandlerGenerator<T : Any> {

	fun generateUpdateHandlerFunctions(metaDataSource: T): Map<String, String>

}