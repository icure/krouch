package org.taktik.couchdb.support

import org.taktik.couchdb.entity.View
import org.taktik.couchdb.support.views.ExternalViewRepository

class ExternalViewGenerator : ViewGenerator<ExternalViewRepository> {

	private fun fullName(baseId: String, secondaryPartition: String?, name: String) =
		if(secondaryPartition != null) "$baseId-$secondaryPartition/$name"
		else "$baseId/$name"

	override fun generateViews(repository: ExternalViewRepository, baseId: String): Map<String, View> =
		repository.views.mapKeys { (k, _) -> fullName(baseId, repository.secondaryPartition, k) }
}