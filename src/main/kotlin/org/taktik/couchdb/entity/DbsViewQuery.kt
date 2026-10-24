package org.taktik.couchdb.entity

import io.icure.asyncjacksonhttpclient.net.addSinglePathComponent
import io.icure.asyncjacksonhttpclient.net.param
import java.net.URI

/**
 * Query on the node-local `_dbs` system database (`/_node/_local/_dbs/_all_docs`), which holds the shard map of
 * every database of the cluster. [couchDBUri] is the root of the CouchDB server, not the URI of a database.
 */
internal data class DbsViewQuery(
	val couchDBUri: URI,
	override val keys: List<Any?>?,
	override val isIncludeDocs: Boolean,
	override val ignoreNotFound: Boolean,
) : ViewQueryLike() {
	override val skipIfViewDoesNotExist: Boolean = false

	override fun buildQuery(): URI = couchDBUri
		.addSinglePathComponent("_node")
		.addSinglePathComponent("_local")
		.addSinglePathComponent("_dbs")
		.addSinglePathComponent("_all_docs")
		.let { if (isIncludeDocs) it.param("include_docs", "true") else it }
}
