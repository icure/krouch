package org.taktik.couchdb.support.generators.lists

interface ListGenerator<T : Any> {

	fun generateListFunctions(metaDataSource: T): Map<String, String>

}