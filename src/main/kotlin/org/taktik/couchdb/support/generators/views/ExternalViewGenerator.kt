package org.taktik.couchdb.support.generators.views

import org.taktik.couchdb.entity.View
import org.taktik.couchdb.support.repositories.ExternalViewRepository

object ExternalViewGenerator : ViewGenerator<ExternalViewRepository> {

	override fun generateViews(
		repository: ExternalViewRepository,
		ddocEntityName: String,
	): Map<ViewGenerator.ViewKey, View> =
		repository.views.mapKeys { (k, _) ->
			ViewGenerator.ViewKey(
				ddocEntityName = ddocEntityName,
				partition = repository.secondaryPartition,
				viewName = k
			)
		}

}