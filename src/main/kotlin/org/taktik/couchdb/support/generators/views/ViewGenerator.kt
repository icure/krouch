package org.taktik.couchdb.support.generators

import org.taktik.couchdb.entity.View

interface ViewGenerator<T> {

	fun generateViews(
		repository: T,
		baseId: String,
	): Map<String, View>

}