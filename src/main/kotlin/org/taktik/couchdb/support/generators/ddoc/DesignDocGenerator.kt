package org.taktik.couchdb.support.generators.ddoc

import org.taktik.couchdb.entity.DesignDocument
import org.taktik.couchdb.entity.View
import org.taktik.couchdb.support.DesignDocumentFactory
import org.taktik.couchdb.support.generators.views.ViewGenerator

abstract class DesignDocGenerator<T : Any> {

	protected abstract fun splitViews(
		entityName: String,
		views: Map<ViewGenerator.ViewKey, View>,
		metadataSource: T
	): Map<DesignDocId, Map<String, View>>
	protected abstract fun generateDdocName(ddocId: DesignDocId, metadataSource: T, views: List<View>, useVersioning: Boolean): String

	/**
	 * @param entityName the simple name of the Entity class that is target by the views in this ddoc.
	 * @param targetPartition if not null, only the ddoc for this partition will be generated.
	 * @param views the output of the [ViewGenerator],
	 * @param metadataSource the source that defines the views.
	 * @param useVersioning whether views should be versioned.
	 * @param initDdoc a function that takes id, partition and views and instantiates the design doc.
	 */
	fun splitViewsAndGenerateDesignDocs(
		entityName: String,
		targetPartition: DesignDocumentFactory.TargetPartition,
		views: Map<ViewGenerator.ViewKey, View>,
		metadataSource: T,
		useVersioning: Boolean,
		initDdoc: (id: String, partition: String?, views: Map<String, View>) -> DesignDocument
	): Set<DesignDocument> = splitViews(entityName, views, metadataSource).mapNotNull { (ddocId, views) ->
		if (
			targetPartition == DesignDocumentFactory.TargetPartition.All ||
				(targetPartition == DesignDocumentFactory.TargetPartition.Unpartitioned && ddocId.partition.isNullOrBlank()) ||
				(targetPartition is DesignDocumentFactory.TargetPartition.Partition && ddocId.partition == targetPartition.name)
		) {
			initDdoc(
				generateDdocName(ddocId, metadataSource, views.values.toList(), useVersioning),
				ddocId.partition,
				views
			)
		} else {
			null
		}
	}.toSet()

	data class DesignDocId(
		val entityName: String,
		val partition: String?
	) {
		val couchDbId: String
			get() = partition?.takeIf { it.isNotBlank() }?.let { "_design/$entityName-$it" } ?: "_design/$entityName"
	}
}