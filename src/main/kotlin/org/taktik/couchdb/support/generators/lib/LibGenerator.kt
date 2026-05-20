package org.taktik.couchdb.support.generators.lib

interface LibGenerator<T : Any> {

	fun generateLibResources(partition: String?, metadataSource: T): Map<String, String>

}