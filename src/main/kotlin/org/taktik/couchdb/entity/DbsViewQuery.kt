package org.taktik.couchdb.entity

import io.icure.asyncjacksonhttpclient.net.addSinglePathComponent
import java.net.URI

internal data class DbsViewQuery(
	override val keys: List<Any?>?,
	override val isIncludeDocs: Boolean,
) : ViewQueryLike() {
	override val ignoreNotFound: Boolean = false
	override val skipIfViewDoesNotExist: Boolean = false

	override fun buildQuery(): URI = URI("_node")
		.addSinglePathComponent("_local")
		.addSinglePathComponent("_dbs")
		.addSinglePathComponent("_all_docs")
}