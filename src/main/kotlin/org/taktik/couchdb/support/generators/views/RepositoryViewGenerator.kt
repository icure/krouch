package org.taktik.couchdb.support.generators.views

import org.taktik.couchdb.entity.View
import org.taktik.couchdb.support.repositories.DesignDocRepository

object RepositoryViewGenerator : ViewGenerator<DesignDocRepository> {
	override fun generateViews(
		repository: DesignDocRepository,
		ddocEntityName: String
	): Map<ViewGenerator.ViewKey, View> = repository.designDocConfigs.flatMap { ddocConfig ->
		ddocConfig.views.map { (viewName, view) ->
			ViewGenerator.ViewKey(
				ddocEntityName = ddocConfig.entity,
				partition = null,
				viewName = viewName
			) to view
		}
	}.toMap()
}