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
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.util.TokenBuffer
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.google.common.reflect.TypeParameter
import com.google.common.reflect.TypeToken
import io.icure.asyncjacksonhttpclient.net.addSinglePathComponent
import io.icure.asyncjacksonhttpclient.net.param
import io.icure.asyncjacksonhttpclient.net.params
import io.icure.asyncjacksonhttpclient.net.web.HttpMethod
import io.icure.asyncjacksonhttpclient.net.web.Request
import io.icure.asyncjacksonhttpclient.net.web.Response
import io.icure.asyncjacksonhttpclient.net.web.WebClient
import io.icure.asyncjacksonhttpclient.parser.EndArray
import io.icure.asyncjacksonhttpclient.parser.EndObject
import io.icure.asyncjacksonhttpclient.parser.FieldName
import io.icure.asyncjacksonhttpclient.parser.JsonEvent
import io.icure.asyncjacksonhttpclient.parser.NumberValue
import io.icure.asyncjacksonhttpclient.parser.StartArray
import io.icure.asyncjacksonhttpclient.parser.StartObject
import io.icure.asyncjacksonhttpclient.parser.StringValue
import io.icure.asyncjacksonhttpclient.parser.copyFromJsonEvent
import io.icure.asyncjacksonhttpclient.parser.nextSingleValueAs
import io.icure.asyncjacksonhttpclient.parser.nextSingleValueAsOrNull
import io.icure.asyncjacksonhttpclient.parser.nextValue
import io.icure.asyncjacksonhttpclient.parser.skipValue
import io.icure.asyncjacksonhttpclient.parser.split
import io.icure.asyncjacksonhttpclient.parser.toJsonEvents
import io.icure.asyncjacksonhttpclient.parser.toObject
import io.netty.handler.codec.http.HttpHeaderNames
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.taktik.couchdb.entity.ActiveTask
import org.taktik.couchdb.entity.AttachmentResult
import org.taktik.couchdb.entity.Change
import org.taktik.couchdb.entity.DatabaseInfoWrapper
import org.taktik.couchdb.entity.DesignDocumentResult
import org.taktik.couchdb.entity.EntityExceptionBehaviour
import org.taktik.couchdb.entity.IdAndRev
import org.taktik.couchdb.entity.Membership
import org.taktik.couchdb.entity.Option
import org.taktik.couchdb.entity.ReplicateCommand
import org.taktik.couchdb.entity.ReplicatorDocument
import org.taktik.couchdb.entity.Scheduler
import org.taktik.couchdb.entity.Security
import org.taktik.couchdb.entity.Versionable
import org.taktik.couchdb.entity.ViewQuery
import org.taktik.couchdb.exception.CouchDbConflictException
import org.taktik.couchdb.exception.CouchDbException
import org.taktik.couchdb.exception.SkippedQueryException
import org.taktik.couchdb.exception.ViewResultException
import org.taktik.couchdb.mango.MangoQuery
import org.taktik.couchdb.mango.MangoResultException
import org.taktik.couchdb.util.appendDocumentOrDesignDocId
import org.taktik.couchdb.util.bufferedChunks
import reactor.core.publisher.Mono
import java.lang.reflect.Type
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

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

private data class BulkUpdateRequest<T : CouchDbDocument>(
    val docs: Collection<T>,
    @JsonProperty("all_or_nothing") val allOrNothing: Boolean = false
)

private data class BulkDeleteRequest(
    val docs: Collection<DeleteRequest>,
    @JsonProperty("all_or_nothing") val allOrNothing: Boolean = false
)

private data class DeleteRequest(
    @JsonProperty("_id") val id: String,
    @JsonProperty("_rev") val rev: String?,
    @JsonProperty("_deleted") val deleted: Boolean = true
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class BulkUpdateResult(val id: String, val rev: String?, val ok: Boolean?, val error: String?, val reason: String?)
data class DocIdentifier(val id: String?, val rev: String?)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ReplicatorResponse(
    val ok: Boolean,
    val id: String? = null,
    val rev: String? = null,
    val error: String? = null,
    val reason: String? = null
)

// Convenience inline methods with reified type params
inline fun <reified K, reified U, reified T> Client.queryViewIncludeDocs(query: ViewQuery, onEntityException: EntityExceptionBehaviour = EntityExceptionBehaviour.Fail): Flow<ViewRowWithDoc<K, U, T>> {
    require(query.isIncludeDocs) { "Query must have includeDocs=true" }
    return queryView(query, K::class.java, U::class.java, T::class.java, onEntityException = onEntityException).filterIsInstance()
}

inline fun <reified K, reified T> Client.queryViewIncludeDocsNoValue(query: ViewQuery, onEntityException: EntityExceptionBehaviour = EntityExceptionBehaviour.Fail): Flow<ViewRowWithDoc<K, Nothing, T>> {
    require(query.isIncludeDocs) { "Query must have includeDocs=true" }
    return queryView(query, K::class.java, Nothing::class.java, T::class.java, onEntityException = onEntityException).filterIsInstance()
}

inline fun <reified V, reified T> Client.queryViewIncludeDocsNoKey(query: ViewQuery, onEntityException: EntityExceptionBehaviour = EntityExceptionBehaviour.Fail): Flow<ViewRowWithDoc<Nothing, V, T>> {
    require(query.isIncludeDocs) { "Query must have includeDocs=true" }
    return queryView(query, Nothing::class.java, V::class.java, T::class.java, onEntityException = onEntityException).filterIsInstance()
}

inline fun <reified K, reified V> Client.queryView(
    query: ViewQuery,
    timeoutDuration: Duration? = null,
    onEntityException: EntityExceptionBehaviour = EntityExceptionBehaviour.Fail
): Flow<ViewRowNoDoc<K, V>> {
    require(!query.isIncludeDocs) { "Query must have includeDocs=false" }
    return queryView(query, K::class.java, V::class.java, Nothing::class.java, timeoutDuration = timeoutDuration, onEntityException = onEntityException).filterIsInstance()
}

inline fun <reified K> Client.queryViewNoValue(query: ViewQuery, onEntityException: EntityExceptionBehaviour = EntityExceptionBehaviour.Fail): Flow<ViewRowNoDoc<K, Nothing>> {
    require(!query.isIncludeDocs) { "Query must have includeDocs=false" }
    return queryView(query, K::class.java, Nothing::class.java, Nothing::class.java, onEntityException = onEntityException).filterIsInstance()
}

inline fun <reified T> Client.queryMango(query: MangoQuery<T>): Flow<MangoQueryResult<T>> {
    return mangoQuery(query, T::class.java).filterIsInstance()
}

suspend inline fun <reified T : CouchDbDocument> Client.get(id: String): T? =
    this.get(id, object : TypeReference<T>() {})

inline fun <reified T : CouchDbDocument> Client.get(ids: List<String>, onEntityException: EntityExceptionBehaviour = EntityExceptionBehaviour.Fail): Flow<T> = this.get(ids, T::class.java, null, onEntityException)

suspend inline fun <reified T : CouchDbDocument> Client.create(entity: T): T = this.create(entity, T::class.java)

suspend inline fun <reified T : CouchDbDocument> Client.update(entity: T): T = this.update(entity, T::class.java)

inline fun <reified T : CouchDbDocument> Client.bulkUpdate(entities: List<T>): Flow<BulkUpdateResult> =
    this.bulkUpdate(entities, T::class.java)

inline fun <reified T : CouchDbDocument> Client.subscribeForChanges(
    classDiscriminator: String,
    noinline classProvider: (String) -> Class<T>?,
    since: String = "now",
    initialBackOffDelay: Long = 100,
    backOffFactor: Int = 2,
    maxDelay: Long = 10000
): Flow<Change<T>> =
    this.subscribeForChanges(
        T::class.java,
        classDiscriminator,
        classProvider,
        since,
        initialBackOffDelay,
        backOffFactor,
        maxDelay
    )

interface Client {
    // CouchDB options
    suspend fun membership(): Membership
    suspend fun getConfigOption(nodeName: String, section: String, key: String): String?
    suspend fun setConfigOption(nodeName: String, section: String, key: String, newValue: String)

    // Check if db exists
    suspend fun exists(): Boolean
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

    // Changes observing
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
    fun allDatabases(): Flow<String>
}

private const val NOT_FOUND_ERROR = "not_found"
private const val ROWS_FIELD_NAME = "rows"
private const val VALUE_FIELD_NAME = "value"
private const val ID_FIELD_NAME = "id"
private const val ERROR_FIELD_NAME = "error"
private const val KEY_FIELD_NAME = "key"
private const val INCLUDED_DOC_FIELD_NAME = "doc"
private const val TOTAL_ROWS_FIELD_NAME = "total_rows"
private const val OFFSET_FIELD_NAME = "offset"
private const val UPDATE_SEQUENCE_NAME = "update_seq"
private const val DOCS_NAME = "docs"
private const val BOOKMARK_NAME = "bookmark"
private const val ERROR_NAME = "error"
private const val COUCHDB_LOCAL_NODE = "nonode@nohost"

interface HeaderHandler {
    suspend fun handle(value: String)
}

@ExperimentalCoroutinesApi
class ClientImpl(
    private val httpClient: WebClient,
    private val couchDBUri: java.net.URI,
    dbName: String,
    /**
     * Function to retrieve credentials as pair username-password.
     * Credentials returned by this provider may change over time.
     */
    private val credentialsProvider: () -> Pair<String, String>,
    private val objectMapper: ObjectMapper = ObjectMapper().also { it.registerKotlinModule() },
    private val headerHandlers: Map<String, HeaderHandler> = mapOf(),
    private val timingHandler: ((Long) -> Mono<Unit>)? = null,
    private val strictMode: Boolean = false,
) : Client {
    private val dbURI = couchDBUri.addSinglePathComponent(dbName)
    private val log = LoggerFactory.getLogger(javaClass.name)

    /**
     * Alternative constructor for client with constant username and password.
     */
    constructor(
        httpClient: WebClient,
        couchDBUri: java.net.URI,
        dbName: String,
        username: String,
        password: String,
        objectMapper: ObjectMapper = ObjectMapper().also { it.registerKotlinModule() },
        headerHandlers: Map<String, HeaderHandler> = mapOf(),
        timingHandler: ((Long) -> Mono<Unit>)? = null,
        strictMode: Boolean = false
    ) : this(
        httpClient,
        couchDBUri,
        dbName,
        { username to password },
        objectMapper,
        headerHandlers,
        timingHandler,
        strictMode
    )

    override suspend fun create(q: Int?, n: Int?, requestId: String?): Boolean {
        val request = newRequest(dbURI.let {
            q?.let { q -> it.param("q", q.toString()) } ?: it
        }.let { n?.let { n -> it.param("n", n.toString()) } ?: it }, "", HttpMethod.PUT)

        val result = request
            .getCouchDbResponse<Map<String, *>?>(true)
        return result?.get("ok") == true
    }

    override suspend fun security(security: Security): Boolean {
        val doc = objectMapper.writerFor(Security::class.java).writeValueAsString(security)

        val request = newRequest(dbURI.addSinglePathComponent("_security"), doc, HttpMethod.PUT)
        val result = request
            .getCouchDbResponse<Map<String, *>?>(true)
        return result?.get("ok") == true
    }

    override suspend fun designDocumentsIds(): Set<String> {
        val request = newRequest(dbURI.addSinglePathComponent("_design_docs"))
        val result = request.getCouchDbResponse<DesignDocumentResult?>(true)
        return result?.rows?.mapNotNull { it.key }?.toSet() ?: emptySet()
    }

    override suspend fun exists(): Boolean {
        val request = newRequest(dbURI)
        val result = request
            .getCouchDbResponse<Map<String, *>?>(true)
        return result?.get("db_name") != null
    }

    override suspend fun membership(): Membership {
        val request = newRequest(couchDBUri.addSinglePathComponent("_membership"))
        return checkNotNull(request.getCouchDbResponse<Membership>(false)) {
            "Cannot get Membership info from the db"
        }
    }

    override suspend fun getConfigOption(nodeName: String, section: String, key: String): String? {
        val uri = couchDBUri
            .addSinglePathComponent("_node")
            .addSinglePathComponent(nodeName)
            .addSinglePathComponent("_config")
            .addSinglePathComponent(section)
            .addSinglePathComponent(key)
        val request = newRequest(uri)
        return request.getCouchDbResponse<String>(true)
    }

    override suspend fun setConfigOption(nodeName: String, section: String, key: String, newValue: String) {
        val uri = couchDBUri
            .addSinglePathComponent("_node")
            .addSinglePathComponent(nodeName)
            .addSinglePathComponent("_config")
            .addSinglePathComponent(section)
            .addSinglePathComponent(key)
        val request = newRequest(
            uri = uri,
            body = objectMapper.writeValueAsString(newValue),
            method = HttpMethod.PUT
        )
        request.getCouchDbResponse<String>(false)
    }

    override suspend fun destroyDatabase(): Boolean {
        val request = newRequest(dbURI, HttpMethod.DELETE)
        val result = request.getCouchDbResponse<Map<String, *>?>(true)
        return result?.get("ok") == true
    }

    @Deprecated("Use overload with TypeReference instead to avoid loss of Generic information in lists")
    override suspend fun <T : CouchDbDocument> get(id: String, clazz: Class<T>, vararg options: Option): T? {
        require(id.isNotBlank()) { "Id cannot be blank" }
        val request = newRequest(
            dbURI.appendDocumentOrDesignDocId(id).params(options.associate { Pair(it.paramName(), listOf("true")) })
        )

        @Suppress("DEPRECATION")
        return request.getCouchDbResponse(clazz, nullIf404 = true)
    }

    override suspend fun <T : CouchDbDocument> get(id: String, type: TypeReference<T>, vararg options: Option): T? {
        require(id.isNotBlank()) { "Id cannot be blank" }
        val request = newRequest(
            dbURI.appendDocumentOrDesignDocId(id).params(options.associate { Pair(it.paramName(), listOf("true")) })
        )

        return request.getCouchDbResponse(type, nullIf404 = true)
    }

    override suspend fun <T : CouchDbDocument> get(
        id: String,
        type: TypeReference<T>,
        requestId: String,
        vararg options: Option
    ): T? {
        require(id.isNotBlank()) { "Id cannot be blank" }
        val request = newRequest(
            dbURI.appendDocumentOrDesignDocId(id).params(options.associate { Pair(it.paramName(), listOf("true")) })
        )

        return request.getCouchDbResponse(type, nullIf404 = true)
    }

    @Deprecated("Use overload with TypeReference instead to avoid loss of Generic information in lists")
    override suspend fun <T : CouchDbDocument> get(
        id: String,
        rev: String,
        clazz: Class<T>,
        vararg options: Option
    ): T? {
        val request = makeAndValidateRequest(id, rev, options)

        @Suppress("DEPRECATION")
        return request.getCouchDbResponse(clazz, nullIf404 = true)
    }

    override suspend fun <T : CouchDbDocument> get(
        id: String,
        rev: String,
        type: TypeReference<T>,
        vararg options: Option
    ): T? {
        val request = makeAndValidateRequest(id, rev, options)

        return request.getCouchDbResponse(type, nullIf404 = true)
    }

    override suspend fun <T : CouchDbDocument> get(
        id: String,
        rev: String,
        type: TypeReference<T>,
        requestId: String,
        vararg options: Option
    ): T? {
        val request = makeAndValidateRequest(id, rev, options)

        return request.getCouchDbResponse(type, nullIf404 = true)
    }

    private fun makeAndValidateRequest(
        id: String,
        rev: String,
        options: Array<out Option>
    ): Request {
        require(id.isNotBlank()) { "Id cannot be blank" }
        require(rev.isNotBlank()) { "Rev cannot be blank" }
        return newRequest(
            dbURI.appendDocumentOrDesignDocId(id)
                .params((listOf("rev" to listOf(rev)) + options.map { Pair(it.paramName(), listOf("true")) }).toMap())
        )
    }

    private data class AllDocsViewValue(val rev: String, val deleted: Boolean? = null)

    override fun <T : CouchDbDocument> get(ids: Collection<String>, clazz: Class<T>, requestId: String?, onEntityException: EntityExceptionBehaviour): Flow<T> {
        return getForPagination(ids, clazz, requestId, onEntityException)
            .filterIsInstance<ViewRowWithDoc<String, AllDocsViewValue, T>>()
            .map { it.doc }
    }

    override fun <T : CouchDbDocument> get(ids: Flow<String>, clazz: Class<T>, requestId: String?, onEntityException: EntityExceptionBehaviour): Flow<T> {
        return getForPagination(ids, clazz, requestId, onEntityException)
            .filterIsInstance<ViewRowWithDoc<String, AllDocsViewValue, T>>()
            .map { it.doc }
    }

    override fun <T : CouchDbDocument> getForPagination(
        ids: Collection<String>,
        clazz: Class<T>,
        requestId: String?,
        onEntityException: EntityExceptionBehaviour
    ): Flow<ViewQueryResultEvent> {
        val viewQuery = ViewQuery()
            .allDocs()
            .includeDocs(true)
            .keys(ids)
            .ignoreNotFound(true)
        return queryView(viewQuery, String::class.java, AllDocsViewValue::class.java, clazz, requestId = requestId, onEntityException = onEntityException)
    }

    override fun <T : CouchDbDocument> getForPagination(
        ids: Flow<String>,
        clazz: Class<T>,
        requestId: String?,
        onEntityException: EntityExceptionBehaviour
    ): Flow<ViewQueryResultEvent> = flow {
        ids.fold(Pair(persistentListOf<String>(), Triple(0, Integer.MAX_VALUE, 0L))) { acc, id ->
            if (acc.first.size == 100) {
                getForPagination(acc.first, clazz, requestId, onEntityException).fold(Pair(persistentListOf(id), acc.second)) { res, it ->
                    when (it) {
                        is ViewRowWithDoc<*, *, *> -> {
                            emit(it)
                            res
                        }

                        is TotalCount -> {
                            Pair(res.first, Triple(res.second.first + it.total, res.second.second, res.second.third))
                        }

                        is Offset -> {
                            Pair(
                                res.first,
                                Triple(res.second.first, min(res.second.second, it.offset), res.second.third)
                            )
                        }

                        is UpdateSequence -> {
                            Pair(res.first, Triple(res.second.first, res.second.second, max(res.second.third, it.seq)))
                        }

                        else -> res
                    }
                }
            } else {
                Pair(acc.first.add(id), acc.second)
            }
        }.let { remainder ->
            if (remainder.first.isNotEmpty())
                getForPagination(remainder.first, clazz, onEntityException = onEntityException).fold(remainder.second) { counters, it ->
                    when (it) {
                        is ViewRowWithDoc<*, *, *> -> {
                            emit(it)
                            counters
                        }

                        is TotalCount -> {
                            Triple(counters.first + it.total, counters.second, counters.third)
                        }

                        is Offset -> {
                            Triple(counters.first, min(counters.second, it.offset), counters.third)
                        }

                        is UpdateSequence -> {
                            Triple(counters.first, counters.second, max(counters.third, it.seq))
                        }

                        else -> counters
                    }
                } else remainder.second
        }.let {
            emit(TotalCount(it.first))
            if (it.second < Integer.MAX_VALUE) {
                emit(Offset(it.second))
            }
            if (it.third > 0L) {
                emit(UpdateSequence(it.third))
            }
        }
    }

    @ExperimentalCoroutinesApi
    override fun getAttachment(id: String, attachmentId: String, rev: String?, requestId: String?): Flow<ByteBuffer> {
        require(id.isNotBlank()) { "Id cannot be blank" }
        require(attachmentId.isNotBlank()) { "attachmentId cannot be blank" }
        val request =
            newRequest(
                dbURI.addSinglePathComponent(id).addSinglePathComponent(attachmentId)
                    .let { u -> rev?.let { u.param("rev", it) } ?: u })

        return request.retrieveAndInjectRequestId(headerHandlers, timingHandler).registerStatusMappers().toBytesFlow()
    }

    override suspend fun deleteAttachment(id: String, attachmentId: String, rev: String, requestId: String?): String {
        require(id.isNotBlank()) { "Id cannot be blank" }
        require(attachmentId.isNotBlank()) { "attachmentId cannot be blank" }
        require(rev.isNotBlank()) { "rev cannot be blank" }

        val uri = dbURI.addSinglePathComponent(id).addSinglePathComponent(attachmentId)
        val request = newRequest(uri.param("rev", rev), HttpMethod.DELETE)

        return request.getCouchDbResponse<AttachmentResult>()!!.rev
    }

    override suspend fun createAttachment(
        id: String,
        attachmentId: String,
        rev: String,
        contentType: String,
        data: Flow<ByteBuffer>,
        requestId: String?
    ): String = coroutineScope {
        require(id.isNotBlank()) { "Id cannot be blank" }
        require(attachmentId.isNotBlank()) { "attachmentId cannot be blank" }
        require(rev.isNotBlank()) { "rev cannot be blank" }

        val uri = dbURI.addSinglePathComponent(id).addSinglePathComponent(attachmentId)
        val request =
            newRequest(uri.param("rev", rev), HttpMethod.PUT, requestId = requestId).header("Content-type", contentType)
                .body(data)

        request.getCouchDbResponse<AttachmentResult>()!!.rev
    }

    // CouchDB Response body for Create/Update/Delete
    private data class CUDResponse(
        val id: String?,
        val rev: String?,
        val ok: Boolean?,
        val error: String?,
        val reason: String?
    )

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : CouchDbDocument> create(entity: T, clazz: Class<T>, requestId: String?): T {
        val uri = dbURI

        val serializedDoc = objectMapper.writerFor(clazz).writeValueAsString(entity)
        val request = newRequest(uri, serializedDoc, requestId = requestId)

        val createResponse = request.getCouchDbResponse<CUDResponse>()!!.also {
            validate(it)
        }
        // Create a new copy of the doc and set rev/id from response
        return entity.withIdRev(createResponse.id, createResponse.rev!!) as T
    }

    private fun validate(response: CUDResponse) {
        response.error?.let { throw java.lang.IllegalStateException("${it}: ${response.reason ?: "-"}") }
        check(response.ok ?: false)
        checkNotNull(response.id)
        checkNotNull(response.rev)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : CouchDbDocument> update(entity: T, clazz: Class<T>, requestId: String?): T {
        val docId = entity.id
        require(docId.isNotBlank()) { "Id cannot be blank" }
        if (strictMode) {
            require(entity.rev != null) { "rev cannot be null" }
            require(entity.rev!!.isNotBlank()) { "rev cannot be blank" }
            require(entity.rev!!.matches(Regex("^[0-9]+-[a-z0-9]+$"))) { "Invalid rev format" }
        } else {
            if (entity.rev.isNullOrBlank()) {
                log.warn("Try to update {} of class {} with null or blank revision", docId, clazz)
            }
        }
        val updateURI = dbURI.addSinglePathComponent(docId)

        val serializedDoc = objectMapper.writerFor(clazz).writeValueAsString(entity)
        val request = newRequest(updateURI, serializedDoc, HttpMethod.PUT, requestId)

        val updateResponse = request.getCouchDbResponse<CUDResponse>()!!.also {
            validate(it)
        }
        // Create a new copy of the doc and set rev/id from response
        return entity.withIdRev(updateResponse.id, updateResponse.rev!!) as T
    }

    override suspend fun <T : CouchDbDocument> delete(entity: T, requestId: String?): DocIdentifier {
        val id = entity.id
        require(id.isNotBlank()) { "Id cannot be blank" }
        require(!entity.rev.isNullOrBlank()) { "Revision cannot be blank" }
        val uri = dbURI.addSinglePathComponent(id).param("rev", entity.rev!!)

        val request = newRequest(uri, HttpMethod.DELETE, requestId = requestId)

        return request.getCouchDbResponse<CUDResponse>()!!.also {
            validate(it)
        }.let { DocIdentifier(it.id, it.rev) }
    }

    override fun <T : CouchDbDocument> bulkUpdate(
        entities: Collection<T>,
        clazz: Class<T>,
        requestId: String?
    ): Flow<BulkUpdateResult> =
        flow {
            coroutineScope {
                entities.forEach {
                    require(it.rev == null || it.rev!!.matches(Regex("^[0-9]+-[a-z0-9]+$"))) { "Rev should be null or have a valid format" }
                }
                emitUpdateResults(this, BulkUpdateRequest(entities), requestId)
            }
        }

    override fun <T : CouchDbDocument> bulkDelete(entities: Collection<T>, requestId: String?): Flow<BulkUpdateResult> =
        bulkDeleteByIdAndRev(
            entities.map { IdAndRev(it.id, it.rev) },
            requestId
        )

    override fun bulkDeleteByIdAndRev(
        entities: Collection<IdAndRev>,
        requestId: String?
    ): Flow<BulkUpdateResult> = flow {
        coroutineScope {
            emitUpdateResults(this, BulkDeleteRequest(entities.map { DeleteRequest(it.id, it.rev) }), requestId)
        }
    }

    private suspend fun FlowCollector<BulkUpdateResult>.emitUpdateResults(
        scope: CoroutineScope,
        requestBody: Any,
        requestId: String?
    ) {
        val uri = dbURI.addSinglePathComponent("_bulk_docs")

        val request = newRequest(uri, objectMapper.writeValueAsString(requestBody), requestId = requestId)

        val asyncParser = objectMapper.createNonBlockingByteArrayParser()
        val jsonEvents =
            request.retrieveAndInjectRequestId(headerHandlers, timingHandler).toJsonEvents(asyncParser).produceIn(scope)
        check(jsonEvents.receive() == StartArray) { "Expected result to start with StartArray" }
        while (true) { // Loop through result array
            val nextValue = jsonEvents.nextValue(asyncParser) ?: break

            val bulkUpdateResult =
                checkNotNull(nextValue.asParser(objectMapper).readValueAs(BulkUpdateResult::class.java))
            emit(bulkUpdateResult)
        }
        jsonEvents.cancel()
    }

    override fun <T> mangoQuery(query: MangoQuery<T>, docType: Class<T>): Flow<ViewQueryResultEvent> = flow {
        coroutineScope {
            val request =
                newRequest(query.generateQueryUrlFrom(dbURI.toString()), objectMapper.writeValueAsString(query))
            val asyncParser = objectMapper.createNonBlockingByteArrayParser()

            /** Execute the request and get the response as a Flow of [JsonEvent] **/
            val jsonEvents =
                request.retrieveAndInjectRequestId(headerHandlers, timingHandler).toJsonEvents(asyncParser)
                    .produceIn(this)

            // Response should be a Json object
            val firstEvent = jsonEvents.receive()
            check(firstEvent == StartObject) { "Expected data to start with an Object" }
            resultLoop@ while (true) { // Loop through result object fields
                when (val nextEvent = jsonEvents.receive()) {
                    EndObject -> break@resultLoop // End of result object
                    is FieldName -> {
                        when (nextEvent.name) {
                            DOCS_NAME -> { // We found the "rows" field
                                // Rows field should be an array
                                check(jsonEvents.receive() == StartArray) { "Expected rows field to be an array" }
                                // At this point we are in the rows array, and StartArray event has been consumed

                                while (jsonEvents.nextValue(asyncParser)?.let {
                                        val doc = it.asParser(objectMapper).readValueAs(docType)
                                        emit(MangoQueryResult(doc, null))
                                    } != null) { // Loop through doc objects
                                }
                            }

                            BOOKMARK_NAME -> {
                                emit(MangoQueryResult(null, jsonEvents.nextSingleValueAs<StringValue>().value))
                            }

                            ERROR_NAME -> {
                                val error = jsonEvents.nextSingleValueAs<StringValue>().value
                                jsonEvents.receive()
                                val reason = jsonEvents.nextSingleValueAs<StringValue>().value
                                throw MangoResultException(error, reason)
                            }

                            else -> jsonEvents.skipValue()
                        }
                    }

                    else -> println("Expected EndObject or FieldName, found $nextEvent")
                }
            }
            jsonEvents.cancel()
        }
    }

    override fun <K, V, T> queryView(
        query: ViewQuery,
        keyType: Class<K>,
        valueType: Class<V>,
        docType: Class<T>,
        timeoutDuration: Duration?,
        requestId: String?,
        onEntityException: EntityExceptionBehaviour
    ): Flow<ViewQueryResultEvent> = flow {
        coroutineScope {
            val start = System.currentTimeMillis()

            val dbQuery = query.dbPath(dbURI.toString())
            val request = buildRequest(dbQuery, timeoutDuration, requestId)

            val asyncParser = objectMapper.createNonBlockingByteArrayParser()

            /** Execute the request and get the response as a Flow of [JsonEvent] **/
            val jsonEvents =
                request.retrieveAndInjectRequestId(headerHandlers, timingHandler).onStatus(404) {
                    if (query.skipIfViewDoesNotExist) {
                        //TODO accept to return null
                        throw SkippedQueryException()
                    } else {
                        throw IllegalArgumentException(it.responseBodyAsString())
                    }
                }.toJsonEvents(asyncParser).produceIn(this)

            // Response should be a Json object
            val firstEvent = jsonEvents.receive()
            check(firstEvent == StartObject) { "Expected data to start with an Object" }
            resultLoop@ while (true) { // Loop through result object fields
                when (val nextEvent = jsonEvents.receive()) {
                    EndObject -> break@resultLoop // End of result object
                    is FieldName -> {
                        when (nextEvent.name) {
                            ROWS_FIELD_NAME -> { // We found the "rows" field
                                // Rows field should be an array
                                check(jsonEvents.receive() == StartArray) { "Expected rows field to be an array" }
                                // At this point we are in the rows array, and StartArray event has been consumed
                                // We iterate over the rows until we encounter the EndArray event
                                rowsLoop@ while (true) { // Loop through "rows" array
                                    when (jsonEvents.receive()) {
                                        StartObject -> {} // Start of a new row
                                        EndArray -> break@rowsLoop  // End of rows array
                                        else -> error("Expected Start of new row or end of row array")
                                    }
                                    // At this point we are in a row object, and StartObject event has been consumed.
                                    // We iterate over the field names and construct the ViewRowWithDoc or ViewRowNoDoc Object,
                                    // until we encounter the EndObject event
                                    var id: String? = null
                                    var key: K? = null
                                    var value: V? = null
                                    var doc: T? = null
                                    var decodeException: Exception? = null
                                    rowLoop@ while (true) { // Loop through row object fields
                                        when (val nextRowEvent = jsonEvents.receive()) {
                                            EndObject -> break@rowLoop // End of row object
                                            is FieldName -> {
                                                val name = nextRowEvent.name
                                                try {
                                                    when (name) {
                                                        // Parse doc id
                                                        ID_FIELD_NAME -> {
                                                            id = (jsonEvents.receive() as? StringValue)?.value
                                                                ?: error("id field should be a string")
                                                        }
                                                        // Parse key
                                                        KEY_FIELD_NAME -> {
                                                            val keyEvents = jsonEvents.nextValue(asyncParser)
                                                                ?: throw IllegalStateException("Invalid json expecting key")
                                                            key = keyEvents.asParser(objectMapper).readValueAs(keyType)
                                                        }
                                                        // Parse value
                                                        VALUE_FIELD_NAME -> {
                                                            val valueEvents = jsonEvents.nextValue(asyncParser)
                                                                ?: throw IllegalStateException("Invalid json field name")
                                                            value = valueEvents.asParser(objectMapper)
                                                                .readValueAs(valueType)
                                                        }
                                                        // Parse doc
                                                        INCLUDED_DOC_FIELD_NAME -> {
                                                            if (dbQuery.isIncludeDocs) {
                                                                jsonEvents.nextValue(asyncParser)?.let {
                                                                    doc = try {
                                                                        it.asParser(objectMapper).readValueAs(docType)
                                                                    } catch (e: Exception) {
                                                                        if (onEntityException == EntityExceptionBehaviour.Fail) {
                                                                            throw e
                                                                        } else {
                                                                            decodeException = e
                                                                            null
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        // Error field
                                                        ERROR_FIELD_NAME -> {
                                                            val error = jsonEvents.nextSingleValueAs<StringValue>()
                                                            val errorMessage = error.value
                                                            if (!ignoreError(dbQuery, errorMessage)) {
                                                                // TODO retrieve key?
                                                                throw ViewResultException(null, errorMessage)
                                                            }
                                                        }
                                                        // Skip other fields values
                                                        else -> jsonEvents.skipValue()
                                                    }
                                                } catch (e: InvalidFormatException) {
                                                    throw IllegalArgumentException(
                                                        "Cannot deserialize item with id: ${id ?: "N/A"}, error in $name",
                                                        e
                                                    )
                                                }
                                            }

                                            else -> error("Expected EndObject or FieldName")
                                        }
                                    }
                                    // We finished parsing a row, emit the result
                                    id?.let {
                                        val row: ViewRow<K, V, T> = if (dbQuery.isIncludeDocs) {
                                            @Suppress("UNCHECKED_CAST")
                                            when {
                                                doc != null -> ViewRowWithDoc(it, key, value, doc) as ViewRow<K, V, T>
                                                doc == null && decodeException != null -> ViewRowWithMalformedDoc(it, key, value, decodeException)
                                                else -> ViewRowWithMissingDoc(it, key, value)
                                            }
                                        } else {
                                            ViewRowNoDoc(it, key, value)
                                        }
                                        emit(row)
                                    } ?: emit(ViewRowNoDoc("", key, value))

                                }
                            }

                            TOTAL_ROWS_FIELD_NAME -> {
                                val totalValue = jsonEvents.nextSingleValueAs<NumberValue<*>>().value
                                emit(TotalCount(totalValue.toInt()))
                            }

                            OFFSET_FIELD_NAME -> {
                                val offsetValue = jsonEvents.nextSingleValueAsOrNull<NumberValue<*>>()?.value ?: -1
                                emit(Offset(offsetValue.toInt()))
                            }

                            UPDATE_SEQUENCE_NAME -> {
                                val offsetValue = jsonEvents.nextSingleValueAs<NumberValue<*>>().value
                                emit(UpdateSequence(offsetValue.toLong()))
                            }

                            ERROR_FIELD_NAME -> {
                                error("Error executing request $request: ${jsonEvents.nextSingleValueAs<StringValue>().value}")
                            }

                            else -> jsonEvents.skipValue()
                        }
                    }

                    else -> error("Expected EndObject or FieldName, found $nextEvent")
                }
            }
            jsonEvents.cancel()

            log.debug("Request {} : timing {} ms", request, System.currentTimeMillis() - start)
        }
    }.catch { e ->
        if (e !is SkippedQueryException) {
            throw e
        }
    }

    override fun <T : CouchDbDocument> subscribeForChanges(
        clazz: Class<T>,
        classDiscriminator: String,
        classProvider: (String) -> Class<T>?,
        since: String,
        initialBackOffDelay: Long,
        backOffFactor: Int,
        maxDelay: Long,
        heartBeatCallback: () -> Unit
    ): Flow<Change<T>> = channelFlow {
        var lastSeq = since
        var retriesWithoutSeqUpdate = 0
        var delayMillis = initialBackOffDelay
        var latestHeartbeat = Instant.now().toEpochMilli()

        while (true) {
            try {
                val collectDeferred = async(Dispatchers.IO) {
                    internalSubscribeForChanges(clazz, lastSeq, classDiscriminator, classProvider) {
                        latestHeartbeat = Instant.now().toEpochMilli()
                        heartBeatCallback()
                    }.cancellable().catch { e ->
                        log.warn("Error detected while listening for changes", e)
                    }.collect { change ->
                        lastSeq = change.seq
                        retriesWithoutSeqUpdate = 0
                        delayMillis = initialBackOffDelay
                        send(change)
                    }
                }

                val watcher = async(Dispatchers.IO) {
                    var guard = true
                    while (guard) {
                        delay(32000)
                        if (latestHeartbeat < Instant.now().toEpochMilli() - 28000) {
                            guard = false
                            collectDeferred.cancel(NoHeartbeatException("No Heartbeat for 30 s."))
                        }
                    }
                }

                collectDeferred.await()

                log.error("End of connection reached while listening for changes on ${dbURI}. Will try to re-subscribe in ${delayMillis}ms")
                watcher.cancel()
                delay(delayMillis)
                log.error("Resubscribing to $dbURI")

                // Attempt to re-subscribe indefinitely, with an exponential backoff
                delayMillis = (delayMillis * backOffFactor).coerceAtMost(maxDelay)
            } catch (e: NoHeartbeatException) {
                if (retriesWithoutSeqUpdate >= 3) {
                    "No changes nor Heartbeat detected for multiple times with seq $lastSeq, aborting subscription. Corrupted change?.".let {
                        log.error(it, e)
                        throw IllegalStateException(it, e)
                    }
                }
                retriesWithoutSeqUpdate++
                log.warn("No heartbeat cancellation $e caught while listening for changes on ${dbURI}. Will try to re-subscribe in ${delayMillis}ms")
                log.warn("Resubscribing to $dbURI")
            }
        }
    }

    override suspend fun activeTasks(): List<ActiveTask> {
        val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
            dbURI.toString().removeSuffix(dbURI.path)
        ))
            .addSinglePathComponent("_active_tasks")
        val request = newRequest(uri)
        return getCouchDbResponse(request)!!
    }

    override fun databaseInfos(ids: Flow<String>): Flow<DatabaseInfoWrapper> = flow {
        val asyncParser = objectMapper.createNonBlockingByteArrayParser()
        val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
            dbURI.toString().removeSuffix(dbURI.path)
        )).addSinglePathComponent("_dbs_info")

        coroutineScope {
            ids.bufferedChunks(20, 100).collect { dbIds ->
                val request = newRequest(uri, objectMapper.writeValueAsString(mapOf("keys" to dbIds)), HttpMethod.POST)
                val jsonEvents =
                    request.retrieveAndInjectRequestId(headerHandlers, timingHandler).toJsonEvents(asyncParser)
                        .produceIn(this)
                val firstEvent = jsonEvents.receive()
                check(firstEvent == StartArray) { "Expected data to start with an Array" }
                while (jsonEvents.nextValue(asyncParser)?.let {
                        emit(it.asParser(objectMapper).readValueAs(DatabaseInfoWrapper::class.java))
                    } != null) { // Loop through doc objects
                }
            }
        }
    }

    override fun allDatabases(): Flow<String> = flow {
        val asyncParser = objectMapper.createNonBlockingByteArrayParser()
        val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
            dbURI.toString().removeSuffix(dbURI.path)
        )).addSinglePathComponent("_all_dbs")

        coroutineScope {
            val request = newRequest(uri)
            val jsonEvents =
                request.retrieveAndInjectRequestId(headerHandlers, timingHandler).toJsonEvents(asyncParser)
                    .produceIn(this)
            val firstEvent = jsonEvents.receive()
            check(firstEvent == StartArray) { "Expected data to start with an Array" }
            while (jsonEvents.nextValue(asyncParser)?.let {
                    emit(it.asParser(objectMapper).nextTextValue())
                } != null) { // Loop through doc objects
            }
        }
    }

    override suspend fun schedulerDocs(): Scheduler.Docs {
        val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
            dbURI.toString().removeSuffix(dbURI.path)
        )).addSinglePathComponent("_scheduler")
            .addSinglePathComponent("docs")

        val request = newRequest(uri)

        return getCouchDbResponse(request) ?: Scheduler.Docs(0, 0, listOf())
    }

    override suspend fun schedulerJobs(): Scheduler.Jobs {
        val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
            dbURI.toString().removeSuffix(dbURI.path)
        )).addSinglePathComponent("_scheduler")
            .addSinglePathComponent("jobs")

        val request = newRequest(uri)

        return getCouchDbResponse(request) ?: Scheduler.Jobs(0, 0, listOf())
    }

    override suspend fun replicate(command: ReplicateCommand): ReplicatorResponse {
        if (!checkReplicatorDB()) return ReplicatorResponse(
            ok = false,
            error = "Replicator DB not found",
            reason = "Cannot fetch replicator DB or cannot create it",
            id = command.id
        )

        val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
            dbURI.toString().removeSuffix(dbURI.path)
        )).addSinglePathComponent("_replicator")

        val serializedCmd = objectMapper.writeValueAsString(command)
        val request = newRequest(uri, HttpMethod.POST)
            .header("Content-type", "application/json")
            .body(serializedCmd)

        return getCouchDbResponse(request)
            ?: ReplicatorResponse(ok = false, error = "404", reason = "replicate command returns null", id = command.id)
    }

    override suspend fun deleteReplication(docId: String): ReplicatorResponse {
        val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
            dbURI.toString().removeSuffix(dbURI.path)
        ))
            .addSinglePathComponent("_replicator/")
            .addSinglePathComponent("_purge")

        return getReplicatorRevisions(docId)?.run {
            val revisionList = revsInfo?.map { it["rev"]!! }
            val body = mapOf(id to revisionList)

            val serializedBody = objectMapper.writeValueAsString(body)
            val request = newRequest(uri, HttpMethod.POST)
                .header("Content-Type", "application/json")
                .body(serializedBody)

            request.getCouchDbResponse<Map<String, *>?>(true)?.get("purged")?.let {
                val purged = it as Map<*, *>
                if (purged.keys.contains(docId)) {
                    ReplicatorResponse(ok = true, id = docId)
                } else {
                    ReplicatorResponse(
                        ok = false,
                        error = "Purge failure",
                        reason = "Id doesn't exist in purged list",
                        id = docId
                    )
                }
            } ?: ReplicatorResponse(ok = false, error = "404", reason = "delete command returns null", id = docId)
        } ?: ReplicatorResponse(
            ok = false,
            error = "Document not found",
            reason = "document with id $docId doesn't exist",
            id = docId
        )
    }

    private suspend fun getReplicatorRevisions(docId: String): ReplicatorDocument? {
        val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
            dbURI.toString().removeSuffix(dbURI.path)
        ))
            .addSinglePathComponent("_replicator/")
            .addSinglePathComponent(docId)
            .param("revs_info", "true")

        val request = newRequest(uri)

        return getCouchDbResponse(request)
    }

    private suspend fun checkReplicatorDB(): Boolean {
        val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
            dbURI.toString().removeSuffix(dbURI.path)
        ))
            .addSinglePathComponent("_replicator")

        val request = newRequest(uri)

        return request.getCouchDbResponse<Map<String, *>?>(true)?.run { true }
            ?: kotlin.run {
                val createRequest = newRequest(uri, HttpMethod.PUT)
                createRequest.getCouchDbResponse<Map<String, *>?>(true)?.run { get("ok") == true } ?: false
            }
    }

    override suspend fun getCouchDBVersion(): String {
        val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
            dbURI.toString().removeSuffix(dbURI.path)
        ))

        val request = newRequest(uri)

        val response = request.getCouchDbResponse<Map<String, *>?>(true)

        return response?.get("version").toString()
    }

    private suspend inline fun <reified T> getCouchDbResponse(request: Request): T? {
        return request.getCouchDbResponse(object : TypeReference<T>() {}, nullIf404 = true)
    }

    @OptIn(FlowPreview::class)
    private fun <T : CouchDbDocument> internalSubscribeForChanges(
        clazz: Class<T>,
        since: String,
        classDiscriminator: String,
        classProvider: (String) -> Class<T>?,
        heartBeatCallback: () -> Unit
    ): Flow<Change<T>> = flow {
        val charset = Charset.forName("UTF-8")

        log.info("Subscribing for changes of class $clazz")
        val asyncParser = objectMapper.createNonBlockingByteArrayParser()
        // Construct request
        val changesRequest = newRequest(
            dbURI.addSinglePathComponent("_changes").param("feed", "continuous")
                .param("heartbeat", "10000")
                .param("include_docs", "true")
                .param("since", since)
        )

        // Get the response as a Flow of CharBuffers (needed to split by line)
        val responseText = changesRequest.retrieveAndInjectRequestId(headerHandlers, timingHandler).toTextFlow()
        // Split by line
        val splitByLine = responseText.split('\n') { heartBeatCallback() }
        // Convert to json events
        val jsonEvents = splitByLine.map { ev ->
            ev.map {
                charset.encode(it)
            }.toJsonEvents(asyncParser)
        }
        // Parse as generic Change Object
        val changes = jsonEvents.map { events ->
            TokenBuffer(asyncParser).let { tb ->
                var level = 0
                val type = events.foldIndexed(null as String?) { index, type, jsonEvent ->
                    tb.copyFromJsonEvent(jsonEvent)

                    when (jsonEvent) {
                        is FieldName -> if (level == 2 && jsonEvent.name == classDiscriminator && index + 1 < events.size) (events[index + 1] as? StringValue)?.value else type
                        is StartArray -> type.also { level++ }
                        is StartObject -> type.also { level++ }
                        is EndObject -> type.also { level-- }
                        is EndArray -> type.also { level-- }
                        else -> type
                    }
                }
                Pair(type, tb)
            }
        }
        changes.collect { (className, buffer) ->
            if (className != null) {
                val changeClass = classProvider(className)
                if (changeClass != null && clazz.isAssignableFrom(changeClass)) {
                    val value = try {
                        val changeType =
                            object : TypeToken<Change<T>>() {}.where(object : TypeParameter<T>() {}, changeClass).type
                        val typeRef = object : TypeReference<Change<T>>() {
                            override fun getType(): Type {
                                return changeType
                            }
                        }
                        // Parse as actual Change object with the correct class
                        buffer.asParser(objectMapper).readValueAs<Change<T>>(typeRef)
                    } catch (e: JsonMappingException) {
                        log.debug("$dbURI Unmarshalling error while deserialising change of class $className", e)
                        null
                    }
                    value?.let { emit(it) }
                }
            }
        }
    }

    private fun newRequest(
        uri: java.net.URI,
        method: HttpMethod = HttpMethod.GET,
        timeoutDuration: Duration? = null,
        requestId: String? = null
    ) =
        httpClient.uri(uri)
            .method(method, timeoutDuration)
            .let {
                val (username, password) = credentialsProvider()
                it.basicAuth(username, password)
            }
            .let { requestId?.let { rid -> it.header("X-Couch-Request-ID", rid) } ?: it }

    private fun newRequest(
        uri: java.net.URI,
        body: String,
        method: HttpMethod = HttpMethod.POST,
        requestId: String? = null
    ) =
        newRequest(uri, method, requestId = requestId)
            .header(HttpHeaderNames.CONTENT_TYPE.toString(), "application/json")
            .body(body)

    private fun buildRequest(query: ViewQuery, timeoutDuration: Duration? = null, requestId: String? = null) =
        if (query.hasMultipleKeys()) {
            newRequest(query.buildQuery(), query.keysAsJson(), requestId = requestId)
        } else {
            newRequest(query.buildQuery(), timeoutDuration = timeoutDuration, requestId = requestId)
        }

    private fun ignoreError(query: ViewQuery, error: String): Boolean {
        return query.ignoreNotFound && NOT_FOUND_ERROR == error
    }

    @Deprecated("Use overload with TypeReference instead to avoid loss of Generic information in lists")
    private suspend fun <T> Request.getCouchDbResponse(
        clazz: Class<T>,
        emptyResponseAsNull: Boolean = false,
        nullIf404: Boolean = false
    ): T? {
        return try {
            @Suppress("DEPRECATION")
            toFlow().toObject(clazz, objectMapper, emptyResponseAsNull)
        } catch (ex: CouchDbException) {
            if (ex.statusCode == 404 && nullIf404) null else throw ex
        }
    }

    private suspend fun <T> Request.getCouchDbResponse(
        type: TypeReference<T>,
        emptyResponseAsNull: Boolean = false,
        nullIf404: Boolean = false
    ): T? {
        return try {
            toFlow().toObject(type, objectMapper, emptyResponseAsNull)
        } catch (ex: CouchDbException) {
            if (ex.statusCode == 404 && nullIf404) null else throw ex
        }
    }

    private fun Request.toFlow() = this
        .retrieveAndInjectRequestId(headerHandlers, timingHandler)
        .registerStatusMappers()
        .toFlow()

    private fun Response.registerStatusMappers() =
        onStatus(403) { response ->
            throw CouchDbException(
                "Unauthorized Access",
                response.statusCode,
                response.responseBodyAsString(),
                couchDbRequestId = response.headers.find { it.key == "X-Couch-Request-ID" }?.value,
                couchDbBodyTime = response.headers.find { it.key == "X-Couchdb-Body-Time" }?.value?.toLong()
            )
        }
            .onStatus(404) { response ->
                throw CouchDbException(
                    "Document not found",
                    response.statusCode,
                    response.responseBodyAsString(),
                    couchDbRequestId = response.headers.find { it.key == "X-Couch-Request-ID" }?.value,
                    couchDbBodyTime = response.headers.find { it.key == "X-Couchdb-Body-Time" }?.value?.toLong()
                )
            }
            .onStatus(409) { response ->
                throw CouchDbConflictException(
                    "Document update Conflict",
                    response.statusCode,
                    response.responseBodyAsString(),
                    couchDbRequestId = response.headers.find { it.key == "X-Couch-Request-ID" }?.value,
                    couchDbBodyTime = response.headers.find { it.key == "X-Couchdb-Body-Time" }?.value?.toLong()
                )
            }

    private suspend inline fun <reified T> Request.getCouchDbResponse(nullIf404: Boolean = false): T? =
        getCouchDbResponse(object : TypeReference<T>() {}, null is T, nullIf404)
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun Request.retrieveAndInjectRequestId(
    headerHandlers: Map<String, HeaderHandler>,
    timingHandler: ((Long) -> Mono<Unit>)?
): Response = this.retrieve().let {
    headerHandlers.entries.fold(it) { resp, (header, handler) ->
        resp.onHeader(header) { value -> mono { handler.handle(value) } }
    }.let { resp -> timingHandler?.let { resp.withTiming(it) } ?: resp }
}

