package org.taktik.couchdb.support.generators.views

import org.taktik.couchdb.entity.View

interface ViewGenerator<T> {

	fun generateViews(repository: T, ddocEntityName: String): Map<ViewKey, View>

	data class ViewKey(val ddocEntityName: String, val partition: String?, val viewName: String)

}