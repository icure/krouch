package org.taktik.couchdb.support.generators.ddoc

import org.taktik.couchdb.entity.DesignDocument
import org.taktik.couchdb.entity.View
import org.taktik.couchdb.support.generators.views.ViewGenerator

abstract class DesignDocGenerator<T : Any> {

	protected abstract fun splitViews(
		entityName: String,
		views: Map<ViewGenerator.ViewKey, View>,
		metadataSource: T
	): Map<DesignDocId, Map<String, View>>
	protected abstract fun generateDdocName(ddocId: DesignDocId, metadataSource: T, views: List<View>, useVersioning: Boolean): String

	fun splitViewsAndGenerateDesignDocs(
		entityName: String,
		views: Map<ViewGenerator.ViewKey, View>,
		metadataSource: T,
		useVersioning: Boolean,
		initDdoc: (id: String, partition: String?, views: Map<String, View>) -> DesignDocument
	): Set<DesignDocument> = splitViews(entityName, views, metadataSource).map { (ddocId, views) ->
		initDdoc(
			generateDdocName(ddocId, metadataSource, views.values.toList(), useVersioning),
			ddocId.partition,
			views
		)
	}.toSet()

	data class DesignDocId(
		val entityName: String,
		val partition: String?
	) {
		val couchDbId: String
			get() = partition?.takeIf { it.isNotBlank() }?.let { "_design/$entityName-$it" } ?: "_design/$entityName"
	}
}