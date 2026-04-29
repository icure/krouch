package org.taktik.couchdb.support.generators.lib

interface LibGenerator<T : Any> {

	fun generateLibResources(metadataSource: T): Map<String, String>

}