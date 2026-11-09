package org.taktik.couchdb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.taktik.couchdb.entity.Versionable

class NoHeartbeatException(msg: String) : CancellationException(msg)

typealias CouchDbDocument = Versionable<String>

/**
 * An event in the [Flow] returned by [Client.queryView]
 */
sealed class ViewQueryResultEvent

data class MangoQueryResult<T>(val doc: T?, val key: String?) : ViewQueryResultEvent()

/**
 * This event contains the total number of result of the query
 */
data class TotalCount(val total: Int) : ViewQueryResultEvent()

/**
 * This event contains the offset of the query
 */
data class Offset(val offset: Int) : ViewQueryResultEvent()

/**
 * This event contains the update sequence of the query
 */
data class UpdateSequence(val seq: Long) : ViewQueryResultEvent()

abstract class ViewRow<out K, out V, out T> : ViewQueryResultEvent() {
	abstract val id: String
	abstract val key: K?
	abstract val value: V?
	abstract val doc: T?
}

data class ViewRowWithDoc<K, V, T>(
	override val id: String,
	override val key: K?,
	override val value: V?,
	override val doc: T
) : ViewRow<K, V, T>()

data class ViewRowWithMalformedDoc<K, V>(
	override val id: String,
	override val key: K?,
	override val value: V?,
	val error: Exception?
) : ViewRow<K, V, Nothing>() {
	override val doc: Nothing
		get() = error("Row has no doc")
}

data class ViewRowNoDoc<K, V>(override val id: String, override val key: K?, override val value: V?) :
	ViewRow<K, V, Nothing>() {
	override val doc: Nothing
		get() = error("Row has no doc")
}

data class ViewRowWithMissingDoc<K, V>(override val id: String, override val key: K?, override val value: V?) :
	ViewRow<K, V, Nothing>() {
	override val doc: Nothing
		get() = error("Doc is missing for this row")
}

internal data class BulkUpdateRequest<T : CouchDbDocument>(
	val docs: Collection<T>,
	@param:JsonProperty("all_or_nothing") val allOrNothing: Boolean = false
)

internal data class BulkDeleteRequest(
	val docs: Collection<DeleteRequest>,
	@param:JsonProperty("all_or_nothing") val allOrNothing: Boolean = false
)

internal data class DeleteRequest(
	@param:JsonProperty("_id") val id: String,
	@param:JsonProperty("_rev") val rev: String?,
	@param:JsonProperty("_deleted") val deleted: Boolean = true
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class BulkUpdateResult(val id: String, val rev: String?, val ok: Boolean?, val error: String?, val reason: String?)
data class DocIdentifier(val id: String?, val rev: String?)

/**
 * One entry of the JSON array CouchDB returns for `GET /db/id?open_revs=all` (plain-JSON, non-multipart form):
 * `[{"ok": {...doc...}}, {"ok": {...doc2...}}]`. Only ever has [ok] set in that response shape.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenRevResult<T>(val ok: T? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class BulkGetRequestDoc(val id: String, val rev: String? = null)
internal data class BulkGetRequest(val docs: Collection<BulkGetRequestDoc>)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class BulkGetResultDoc<T>(val ok: T? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class BulkGetResult<T>(val id: String? = null, val docs: List<BulkGetResultDoc<T>> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class BulkGetResponse<T>(val results: List<BulkGetResult<T>>)

internal data class BulkImportRequest<T : CouchDbDocument>(
	val docs: Collection<T>,
	@param:JsonProperty("new_edits") val newEdits: Boolean = false
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ReplicatorResponse(
	val ok: Boolean,
	val id: String? = null,
	val rev: String? = null,
	val error: String? = null,
	val reason: String? = null
)

interface HeaderHandler {
	suspend fun handle(value: String)
}