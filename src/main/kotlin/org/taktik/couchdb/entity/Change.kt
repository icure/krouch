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