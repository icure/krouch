package org.taktik.couchdb.support.generators.ddoc

import org.apache.commons.codec.digest.DigestUtils
import org.taktik.couchdb.entity.View
import org.taktik.couchdb.support.generators.views.ViewGenerator

class DefaultDesignDocGenerator<T : Any> : DesignDocGenerator<T>() {

	private fun createViewVersionHash(views: List<View>): String =
		DigestUtils.sha256Hex(views.sortedBy { it.map }.joinToString { it.toString() }).substring(0, 4)

	override fun splitViews(views: Map<ViewGenerator.ViewKey, View>, metadataSource: T): Map<DesignDocId, Map<String, View>> =
		views.toList().groupBy { (k,_) -> DesignDocId(k.ddocEntityName, k.partition) }.mapValues { (_, v) ->
			v.associateTo(LinkedHashMap()) {
				it.first.viewName to it.second
			}
		}

	override fun generateDdocName(
		ddocId: DesignDocId,
		metadataSource: T,
		views: List<View>,
		useVersioning: Boolean
	): String = if (useVersioning) {
			"${ddocId.couchDbId}_${createViewVersionHash(views)}"
		} else {
			ddocId.couchDbId
		}

}