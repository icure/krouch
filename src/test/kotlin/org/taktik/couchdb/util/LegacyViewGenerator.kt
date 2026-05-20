package org.taktik.couchdb.util

import org.taktik.couchdb.entity.View

interface LegacyViewGenerator<T> {

	fun generateViews(
		repository: T,
		baseId: String,
	): Map<String, View>

}