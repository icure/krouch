package org.taktik.couchdb.support.generators.ddoc

import org.taktik.couchdb.entity.DesignDocument
import org.taktik.couchdb.entity.View
import org.taktik.couchdb.support.generators.views.ViewGenerator

abstract class DesignDocGenerator<T : Any> {

	protected abstract fun splitViews(views: Map<ViewGenerator.ViewKey, View>, metadataSource: T): Map<DesignDocId, Map<String, View>>
	protected abstract fun generateDdocName(ddocId: DesignDocId, metadataSource: T, views: List<View>, useVersioning: Boolean): String

	fun splitViewsAndGenerateDesignDocs(
		views: Map<ViewGenerator.ViewKey, View>,
		metadataSource: T,
		useVersioning: Boolean,
		initDdoc: (id: String, views: Map<String, View>) -> DesignDocument
	): Set<DesignDocument> = splitViews(views, metadataSource).map { (ddocId, views) ->
		initDdoc(
			generateDdocName(ddocId, metadataSource, views.values.toList(), useVersioning),
			views
		)
	}.toSet()

	data class DesignDocId(
		val entityName: String,
		val partition: String?
	) {
		val couchDbId: String
			get() = partition?.let { "_design/$entityName-$it" } ?: "_design/$entityName"
	}
}