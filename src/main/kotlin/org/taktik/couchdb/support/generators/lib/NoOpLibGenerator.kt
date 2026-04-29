package org.taktik.couchdb.support.generators.lib

class NoOpLibGenerator<T : Any> : LibGenerator<T> {
	override fun generateLibResources(metadataSource: T): Map<String, String> = emptyMap()
}