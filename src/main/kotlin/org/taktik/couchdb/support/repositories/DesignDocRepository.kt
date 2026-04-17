package org.taktik.couchdb.support.repositories

import org.taktik.couchdb.entity.DesignDocument
import org.taktik.couchdb.entity.View

data class DesignDocRepository(
	val designDocConfigs: List<DesignDocConfig>,
)

data class DesignDocConfig(
	val entity: String,
	val views: Map<String, View>,
	val currentDocuments: List<DesignDocument>
)