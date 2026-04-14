package org.taktik.couchdb.support.generators.shows

interface ShowGenerator<T : Any> {

	fun generateShowFunctions(metaDataSource: T): Map<String, String>

}