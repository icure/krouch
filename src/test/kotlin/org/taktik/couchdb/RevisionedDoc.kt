/*
 *    Copyright 2020 Taktik SA
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package org.taktik.couchdb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * CouchDB's replication-protocol ancestor history, as embedded in a document body when fetched with
 * `revs=true`, and as consumed by `_bulk_docs` when writing with `new_edits=false`.
 */
data class DocRevisions(val start: Int, val ids: List<String>)

/**
 * Minimal test entity that (unlike [Code]) also carries `_revisions`, used to exercise
 * [Client.getAllLeafRevisions] and [Client.bulkImportWithoutNewEdits].
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class RevisionedDoc(
	@param:JsonProperty("_id") override val id: String,
	@param:JsonProperty("_rev") override val rev: String? = null,
	@param:JsonProperty("_deleted") val deleted: Boolean? = null,
	@param:JsonProperty("_revisions") val revisions: DocRevisions? = null,
	@param:JsonProperty("_conflicts") val conflicts: List<String>? = null,
	val value: String? = null,
) : CouchDbDocument {
	override fun withIdRev(id: String?, rev: String) = if (id != null) this.copy(id = id, rev = rev) else this.copy(rev = rev)
}
