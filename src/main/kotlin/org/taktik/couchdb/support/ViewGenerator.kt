package org.taktik.couchdb.support

import org.taktik.couchdb.entity.View

interface ViewGenerator<T> {

	fun generateViews(
		repository: T,
		baseId: String,
	): Map<String, View>

}