package org.taktik.couchdb.support.generators.lists

class NoOpListGenerator<T : Any> : ListGenerator<T> {
	override fun generateListFunctions(metaDataSource: T): Map<String, String> = emptyMap()
}