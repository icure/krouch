package org.taktik.couchdb.support.generators.shows

class NoOpShowGenerator<T : Any> : ShowGenerator<T> {
	override fun generateShowFunctions(metaDataSource: T): Map<String, String> = emptyMap()
}