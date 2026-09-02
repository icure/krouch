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

package org.taktik.couchdb.entity

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

data class Change<out T>(val seq: String, val id: String, val changes: List<Any>, val doc: T, val deleted: Boolean = false) {
    override fun toString(): String {
        return "Change(seq=$seq, id=$id, changes=$changes, deleted=$deleted)"
    }
}

data class ChangesChunk<out T>(
    /**
     * Last change included in the [results], or if empty the last change done on the DB
     */
    val last_seq: String,
    /**
     * Number of changes after [last_seq]. Note that of pending doesn't consider any filter.
     * If you use a filter even and pending > 0 doing a request with [last_seq] may give empty results (but updated
     * last_seq).
     */
    val pending: Long,
    val results: List<Change<T>>
)
/** The `style` of a `_changes` request: which leaf revisions each row lists. */
enum class ChangesStyle(val value: String) {
    /** Every leaf revision of the document (open and deleted conflicts alike). */
    ALL_DOCS("all_docs"),
    /** The winning revision only (CouchDB's default). */
    MAIN_ONLY("main_only"),
}

/** One `changes[]` entry of a `_changes` row: a leaf revision id. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChangeRevision(val rev: String)

/**
 * One row of the unfiltered, document-less `_changes` feed (see `Client.getChangesPage`): the document's id,
 * the `seq` of its latest change, whether it is deleted, and its leaf revisions (every leaf with
 * [ChangesStyle.ALL_DOCS], only the winning one with [ChangesStyle.MAIN_ONLY]).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChangeRow(
    val seq: String,
    val id: String,
    val changes: List<ChangeRevision> = emptyList(),
    val deleted: Boolean = false,
) {
    /** The leaf revision ids listed in [changes]. */
    val revs: List<String>
        @JsonIgnore get() = changes.map { it.rev }
}

/**
 * One page of `_changes?feed=normal`: pass [lastSeq] as the next request's `since` to continue; an empty
 * [results] means the feed is exhausted. [pending] is CouchDB's count of rows after [lastSeq].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChangesPage(
    @param:JsonProperty("last_seq") val lastSeq: String,
    val pending: Long? = null,
    val results: List<ChangeRow> = emptyList(),
)
