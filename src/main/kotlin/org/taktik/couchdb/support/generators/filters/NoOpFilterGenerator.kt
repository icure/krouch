package org.taktik.couchdb.support.generators.filters

class NoOpFilterGenerator<T : Any> : FilterGenerator<T> {
	override fun generateFilterFunctions(metaDataSource: T): Map<String, String> = emptyMap()
}