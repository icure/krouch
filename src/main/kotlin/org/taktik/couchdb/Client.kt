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

import com.fasterxml.jackson.core.type.TypeReference
import kotlinx.coroutines.flow.Flow
import org.taktik.couchdb.entity.ActiveTask
import org.taktik.couchdb.entity.Change
import org.taktik.couchdb.entity.ChangesChunk
import org.taktik.couchdb.entity.ChangesPage
import org.taktik.couchdb.entity.ChangesStyle
import org.taktik.couchdb.entity.DatabaseInfoWrapper
import org.taktik.couchdb.entity.EntityExceptionBehaviour
import org.taktik.couchdb.entity.IdAndRev
import org.taktik.couchdb.entity.Membership
import org.taktik.couchdb.entity.Option
import org.taktik.couchdb.entity.ReplicateCommand
import org.taktik.couchdb.entity.Scheduler
import org.taktik.couchdb.entity.Security
import org.taktik.couchdb.entity.ShardInfo
import org.taktik.couchdb.entity.ViewQuery
import org.taktik.couchdb.impl.AbstractJacksonClient
import org.taktik.couchdb.mango.MangoQuery
import org.taktik.couchdb.queryresult.ViewQueryResultEvent
import org.taktik.couchdb.response.BulkUpdateResult
import org.taktik.couchdb.response.DocIdentifier
import org.taktik.couchdb.response.ReplicatorResponse
import java.nio.ByteBuffer
import java.time.Duration

interface Client {
    // CouchDB options
    suspend fun membership(): Membership
    suspend fun getConfigOption(nodeName: String, section: String, key: String): String?
    suspend fun setConfigOption(nodeName: String, section: String, key: String, newValue: String)

    // Check if db exists
    suspend fun exists(): Boolean

    /**
     * Get the shard info of the database. This information is cached indefinitely, refreshed only when called with
     * [ignoreCache] true.
     */
    suspend fun shards(ignoreCache: Boolean = false): ShardInfo

    suspend fun destroyDatabase(): Boolean

    // CRUD methods
    suspend fun <T : CouchDbDocument> get(id: String, clazz: Class<T>, vararg options: Option): T?
    suspend fun <T : CouchDbDocument> get(id: String, type: TypeReference<T>, vararg options: Option): T?
    suspend fun <T : CouchDbDocument> get(
        id: String,
        type: TypeReference<T>,
        requestId: String,
        vararg options: Option
    ): T?
    /**
     * Get an entity from at least [quorum] nodes before returning.
     * The quorum is coerced at most to the number of "active" nodes (not down, not in maintenance mode). A node being
     * down (or in maintenance mode) doesn't impact this method.
     * If a node is reporting itself as up but is unresponsive and the system has to get a response from that node
     * to reach the quorum then the request will time out.
     *
     * For more info http://events17.linuxfoundation.org/sites/events/files/slides/ABDE_mike_wallace_couchdb_awkward_bits.pdf
     */
    suspend fun <T : CouchDbDocument> getWithQuorum(
        id: String,
        type: TypeReference<T>,
        quorum: Int,
        requestId: String? = null,
        rev: String? = null,
        timeout: Duration? = null,
        vararg options: Option
    ): T?
    suspend fun <T : CouchDbDocument> getWithQuorum(
        id: String,
        clazz: Class<T>,
        quorum: Int,
        requestId: String? = null,
        rev: String? = null,
        timeout: Duration? = null,
        vararg options: Option
    ): T?

    suspend fun <T : CouchDbDocument> get(id: String, rev: String, clazz: Class<T>, vararg options: Option): T?
    suspend fun <T : CouchDbDocument> get(id: String, rev: String, type: TypeReference<T>, vararg options: Option): T?
    suspend fun <T : CouchDbDocument> get(
        id: String,
        rev: String,
        type: TypeReference<T>,
        requestId: String,
        vararg options: Option
    ): T?

    fun <T : CouchDbDocument> get(
        ids: Collection<String>,
        clazz: Class<T>,
        requestId: String? = null,
        onEntityException: EntityExceptionBehaviour = EntityExceptionBehaviour.Fail
    ): Flow<T>
    fun <T : CouchDbDocument> get(
        ids: Flow<String>,
        clazz: Class<T>,
        requestId: String? = null,
        onEntityException: EntityExceptionBehaviour = EntityExceptionBehaviour.Fail
    ): Flow<T>
    fun <T : CouchDbDocument> getForPagination(
        ids: Collection<String>,
        clazz: Class<T>,
        requestId: String? = null,
        onEntityException: EntityExceptionBehaviour = EntityExceptionBehaviour.Fail
    ): Flow<ViewQueryResultEvent>

    fun <T : CouchDbDocument> getForPagination(
        ids: Flow<String>,
        clazz: Class<T>,
        requestId: String? = null,
        onEntityException: EntityExceptionBehaviour = EntityExceptionBehaviour.Fail
    ): Flow<ViewQueryResultEvent>

    fun getAttachment(
        id: String,
        attachmentId: String,
        rev: String? = null,
        requestId: String? = null
    ): Flow<ByteBuffer>

    suspend fun createAttachment(
        id: String,
        attachmentId: String,
        rev: String,
        contentType: String,
        data: Flow<ByteBuffer>,
        requestId: String? = null
    ): String

    suspend fun deleteAttachment(id: String, attachmentId: String, rev: String, requestId: String? = null): String
    suspend fun <T : CouchDbDocument> create(entity: T, clazz: Class<T>, requestId: String? = null): T
    suspend fun <T : CouchDbDocument> update(entity: T, clazz: Class<T>, requestId: String? = null): T

    /**
     * Creates an entity using a quorum for the request, and returns a pair of the entity with the updated revision and
     * if the quorum was reached or not.
     * If the quorum of the request is greater than the number of active nodes then the second item of the pair will be
     * false.
     * If the quorum is not reached within a timeout but at least one write was successful then again the second item
     * will be false.
     * If all nodes responded within the time then the second item will be true.
     *
     * # Timeout
     * [timeout] is the max time to wait for a response before giving up.
     * If a node is unresponsive and the quorum requires a response from all nodes then the couchdb request would wait
     * until the fabric timeout is reached on that node, which could be very long.
     * Currently, couchdb doesn't support a configuration of the timeout on a per-request basis, so this timeout is
     * only applied to the client
     * Note that if the timeout is reached the document might have already been created on some nodes.
     */
    suspend fun <T : CouchDbDocument> createWithQuorum(
        entity: T,
        type: TypeReference<T>,
        quorum: Int,
        timeout: Duration? = null,
        requestId: String? = null
    ): Pair<T,Boolean>
    suspend fun <T : CouchDbDocument> createWithQuorum(
        entity: T,
        type: Class<T>,
        quorum: Int,
        timeout: Duration? = null,
        requestId: String? = null
    ): Pair<T,Boolean>
    /**
     * Same as [createWithQuorum] but for already existing entities
     */
    suspend fun <T : CouchDbDocument> updateWithQuorum(
        entity: T,
        type: TypeReference<T>,
        quorum: Int,
        timeout: Duration? = null,
        requestId: String? = null
    ): Pair<T,Boolean>
    suspend fun <T : CouchDbDocument> updateWithQuorum(
        entity: T,
        type: Class<T>,
        quorum: Int,
        timeout: Duration? = null,
        requestId: String? = null
    ): Pair<T,Boolean>
    fun <T : CouchDbDocument> bulkUpdate(
        entities: Collection<T>,
        clazz: Class<T>,
        requestId: String? = null
    ): Flow<BulkUpdateResult>

    suspend fun <T : CouchDbDocument> delete(entity: T, requestId: String? = null): DocIdentifier
    fun <T : CouchDbDocument> bulkDelete(entities: Collection<T>, requestId: String? = null): Flow<BulkUpdateResult>

    fun bulkDeleteByIdAndRev(entities: Collection<IdAndRev>, requestId: String? = null): Flow<BulkUpdateResult>

    // Query
    fun <K, V, T> queryView(
        query: ViewQuery,
        keyType: Class<K>,
        valueType: Class<V>,
        docType: Class<T>,
        timeoutDuration: Duration? = null,
        requestId: String? = null,
        onEntityException: EntityExceptionBehaviour = EntityExceptionBehaviour.Fail
    ): Flow<ViewQueryResultEvent>

    fun <T> mangoQuery(query: MangoQuery<T>, docType: Class<T>): Flow<ViewQueryResultEvent>

    /**
     * @param since Start giving changes from since, excluded. Can be "0" (from the start), "now" (returns just the
     * next last_seq and no entries), or a previous [ChangesChunk.last_seq].
     * @param limit maximum number of entities to return in the chunk. Note that this limit applies after the filtering:
     * couchdb may analyze more changes than [limit] to find enough entries to satisfy it.
     * @param classDiscriminator property on the entity that contains the class canonical name
     * @param discriminatorValue value for entities of type T in [classDiscriminator]
     */
    // Changes observing
    suspend fun <T : CouchDbDocument> getChanges(
        typeReference: TypeReference<ChangesChunk<T>>,
        since: String,
        limit: Int,
        classDiscriminator: String,
        discriminatorValue: String
    ): ChangesChunk<T>

    /**
     * WARNING: consumes a connection permanently
     */
    fun <T : CouchDbDocument> subscribeForChanges(
        clazz: Class<T>,
        classDiscriminator: String,
        classProvider: (String) -> Class<T>?,
        since: String = "now",
        initialBackOffDelay: Long = 100,
        backOffFactor: Int = 2,
        maxDelay: Long = 10000,
        heartBeatCallback: () -> Unit = {}
    ): Flow<Change<T>>

    suspend fun activeTasks(): List<ActiveTask>
    suspend fun create(q: Int?, n: Int?, requestId: String? = null): Boolean
    suspend fun security(security: Security): Boolean
    suspend fun designDocumentsIds(): Set<String>
    suspend fun schedulerDocs(): Scheduler.Docs
    suspend fun schedulerJobs(): Scheduler.Jobs
    suspend fun replicate(command: ReplicateCommand): ReplicatorResponse
    suspend fun deleteReplication(docId: String): ReplicatorResponse
    suspend fun getCouchDBVersion(): String
    fun databaseInfos(ids: Flow<String>): Flow<DatabaseInfoWrapper>
    fun allDatabases(startkey: String? = null, endkey: String? = null): Flow<String>

    /**
     * Fetches every leaf revision of a document: the winning revision plus every open and deleted conflict,
     * each with its `_revisions` ancestor-chain embedded (so it can be replayed elsewhere with
     * [bulkImportWithoutNewEdits]). Backed by CouchDB's `open_revs=all&revs=true`.
     *
     * Default implementation throws: only [AbstractJacksonClient] provides a real implementation, so implementers that
     * delegate to [Client] without overriding this (e.g. decorators over multiple clients) keep compiling and
     * simply don't support this operation unless they choose to override it too.
     */
    suspend fun <T : CouchDbDocument> getAllLeafRevisions(
        id: String,
        clazz: Class<T>,
        includeAttachmentsData: Boolean = false,
        requestId: String? = null,
    ): List<T>

    /**
     * Writes entities to `_bulk_docs` with `new_edits=false`, so CouchDB accepts each entity's own `_rev`
     * (and, if present, `_revisions` ancestor history) verbatim instead of generating new revisions. Used to
     * faithfully replay a revision/conflict tree captured via [getAllLeafRevisions] into another database.
     *
     * Default implementation throws, for the same reason as [getAllLeafRevisions].
     */
    fun <T : CouchDbDocument> bulkImportWithoutNewEdits(
        entities: Collection<T>,
        clazz: Class<T>,
        requestId: String? = null,
    ): Flow<BulkUpdateResult>

    /**
     * Fetches specific (id, rev) pairs in a single request, via CouchDB's `_bulk_get`. When a pair's `rev`
     * is null, CouchDB returns *every* leaf revision of that id (open and deleted conflicts alike, each with
     * its `_revisions` ancestor chain embedded via `revs=true`) - the same set [getAllLeafRevisions] returns
     * for one id, but for as many ids as you like in one HTTP request. Pairs that don't resolve to a real
     * document/revision are silently skipped.
     *
     * This is the primitive that makes archiving a whole database's revision/conflict trees cost on the
     * order of one request per *batch* of ids (e.g. 1000) rather than one request per document.
     */
    suspend fun <T : CouchDbDocument> getBulkByIdsAndRevs(
        idsAndRevs: Collection<Pair<String, String?>>,
        clazz: Class<T>,
        requestId: String? = null,
    ): List<T>

    /**
     * One page of the database's `_changes` feed (`feed=normal`), unfiltered and without document bodies:
     * every row names a document id, the `seq` of its latest change, whether it is deleted, and - with the
     * default [ChangesStyle.ALL_DOCS] - every one of its leaf revisions. This is the only CouchDB endpoint that
     * enumerates deleted documents (tombstones), which `_all_docs` never lists. Page by passing the returned
     * [ChangesPage.lastSeq] as the next [since]; an empty [ChangesPage.results] means the feed is exhausted.
     */
    suspend fun getChangesPage(
        since: String,
        limit: Int,
        style: ChangesStyle = ChangesStyle.ALL_DOCS,
        requestId: String? = null,
    ): ChangesPage
}
