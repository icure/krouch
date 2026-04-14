package org.taktik.couchdb.support.generators.filters

interface FilterGenerator<T : Any> {

	fun generateFilterFunctions(metaDataSource: T): Map<String, String>

}