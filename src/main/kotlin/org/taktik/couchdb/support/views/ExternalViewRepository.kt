package org.taktik.couchdb.support.views

import org.taktik.couchdb.entity.View

data class ExternalViewRepository(
	val secondaryPartition: String?,
	val klass: Class<*>,
	val views: Map<String, View>
)