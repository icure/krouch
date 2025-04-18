package org.taktik.couchdb

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.content.ChannelWriterContent
import io.ktor.http.content.WriterContent
import io.ktor.http.takeFrom
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import org.slf4j.LoggerFactory
import org.taktik.couchdb.entity.AttachmentResult
import org.taktik.couchdb.entity.DesignDocumentResult
import org.taktik.couchdb.entity.Membership
import org.taktik.couchdb.entity.Option
import org.taktik.couchdb.entity.Security
import org.taktik.couchdb.exception.CouchDbConflictException
import org.taktik.couchdb.exception.CouchDbException
import java.nio.ByteBuffer
import java.time.Duration
import kotlin.coroutines.coroutineContext


@ExperimentalCoroutinesApi
abstract class ClientImplKtor(
	private val httpClient: HttpClient,
	private val couchDBUri: java.net.URI,
	private val dbName: String,
	/**
	 * Function to retrieve credentials as pair username-password.
	 * Credentials returned by this provider may change over time.
	 */
	private val credentialsProvider: () -> Pair<String, String>,
	private val objectMapper: ObjectMapper = ObjectMapper().also { it.registerKotlinModule() },
	private val headerHandlers: Map<String, HeaderHandler> = mapOf(),
	private val timingHandler: (suspend (Long) -> Unit)? = null,
	private val strictMode: Boolean = false,
) : Client {
	//    private val dbURI = couchDBUri.addSinglePathComponent(dbName)
	private val log = LoggerFactory.getLogger(javaClass.name)

	init {
		require(httpClient.attributes.contains(HttpTimeout.key)) {
			"Http client must have HttpTimeout plugin."
		}
	}

	private sealed interface RequestBody {
		fun setOn(requestBuilder: HttpRequestBuilder)
	}

	class TextBody(val text: String, val contentType: ContentType): RequestBody {
		override fun setOn(requestBuilder: HttpRequestBuilder) {
			//!NOTE:
			// WriterContent was the most efficient way of setting an encoded body I could find.
			// Other solutions like TextContent are extremely slow as of ktor 3.0.3
			requestBuilder.setBody(WriterContent(contentType = contentType, body = { write(text) }))
		}
	}

	class DataStreamBody(val data: Flow<ByteBuffer>, val contentType: ContentType): RequestBody {
		override fun setOn(requestBuilder: HttpRequestBuilder) {
			requestBuilder.setBody(ChannelWriterContent(contentType = contentType, body = {
				data.collect { this.writeFully(it) }
			}))
		}
	}

	private inline fun <reified T> JsonBody(obj: T): TextBody =
		TextBody(
			objectMapper.writerFor(object : TypeReference<T>() {}).writeValueAsString(obj),
			ContentType.Application.Json
		)

	private fun <T> JsonBody(obj: T, clazz: Class<T>): TextBody =
		TextBody(
			objectMapper.writerFor(clazz).writeValueAsString(obj),
			ContentType.Application.Json
		)

	/**
	 * Alternative constructor for client with constant username and password.
	 */
	constructor(
		httpClient: HttpClient,
		couchDBUri: java.net.URI,
		dbName: String,
		username: String,
		password: String,
		objectMapper: ObjectMapper = ObjectMapper().also { it.registerKotlinModule() },
		headerHandlers: Map<String, HeaderHandler> = mapOf(),
		timingHandler: (suspend (Long) -> Unit)? = null,
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

	private fun prepareRequest(
		method: HttpMethod,
		vararg additionalPathSegments: String,
		queryParameters: List<Pair<String, String?>> = emptyList(),
		timeoutDuration: Duration? = null,
		requestId: String? = null,
		body: RequestBody? = null,
		includeDbPath: Boolean = true,
	): HttpRequestBuilder = HttpRequestBuilder().apply {
		this.method = method
		url {
			takeFrom(couchDBUri)
			if (includeDbPath) appendPathSegments(dbName)
			additionalPathSegments.forEach {
				appendPathSegments(it.trim('/'))
			}
		}
		queryParameters.forEach { (name, value) ->
			parameter(name, value)
		}
		requestId?.also {
			header("X-Couch-Request-ID", it)
		}
		credentialsProvider().let { (username, password) ->
			basicAuth(username, password)
		}
		timeout {
			requestTimeoutMillis = timeoutDuration?.toMillis()
			socketTimeoutMillis = 60_000
			connectTimeoutMillis = 15_000
		}
		body?.setOn(this)
	}

	private suspend fun <T> HttpRequestBuilder.request(
		ignore404: Boolean = false,
		transformResponse: suspend (HttpResponse) -> T
	): T {
		val start = System.currentTimeMillis()
		return try {
			httpClient.prepareRequest(this).execute { response ->
				headerHandlers.forEach { (header, handler) ->
					response.headers.getAll(header)?.forEach { headerValue ->
						handler.handle(headerValue)
					}
				}
				when {
					response.status.value == 403 -> throw CouchDbException(
						"Unauthorized Access",
						response.status.value,
						response.bodyAsText(),
						couchDbRequestId = response.headers["X-Couch-Request-ID"],
						couchDbBodyTime = response.headers["X-Couchdb-Body-Time"]?.toLong()
					)
					response.status.value == 404 && !ignore404 -> throw CouchDbException(
						"Document not found",
						response.status.value,
						response.bodyAsText(),
						couchDbRequestId = response.headers["X-Couch-Request-ID"],
						couchDbBodyTime = response.headers["X-Couchdb-Body-Time"]?.toLong()
					)
					response.status.value == 409 -> throw CouchDbConflictException(
						"Document update Conflict",
						response.status.value,
						response.bodyAsText(),
						couchDbRequestId = response.headers["X-Couch-Request-ID"],
						couchDbBodyTime = response.headers["X-Couchdb-Body-Time"]?.toLong()
					)
				}
				transformResponse(response)
			}
		} finally {
			timingHandler?.also { it(System.currentTimeMillis() - start) }
		}
	}

	private suspend fun <T> HttpRequestBuilder.requestAndGetNull404(typeReference: TypeReference<T>): T? =
		request(true) {
			if (it.status.value == 404) {
				null
			} else {
				objectMapper.readValue(it.bodyAsChannel().toInputStream(coroutineContext.job), typeReference)
			}
		}

	private suspend fun <T> HttpRequestBuilder.requestAndGetNull404(clazz: Class<T>): T? =
		request(true) {
			if (it.status.value == 404) {
				null
			} else {
				objectMapper.readValue(it.bodyAsChannel().toInputStream(coroutineContext.job), clazz)
			}
		}

	private suspend inline fun <reified T> HttpRequestBuilder.requestAndGetNull404(): T? =
		requestAndGetNull404(object : TypeReference<T>() {})

	private suspend fun <T> HttpRequestBuilder.requestAndGet(typeReference: TypeReference<T>): T =
		request {
			objectMapper.readValue(it.bodyAsChannel().toInputStream(coroutineContext.job), typeReference)
		}

	private suspend fun <T> HttpRequestBuilder.requestAndGet(clazz: Class<T>): T =
		request {
			objectMapper.readValue(it.bodyAsChannel().toInputStream(coroutineContext.job), clazz)
		}

	private suspend inline fun <reified T> HttpRequestBuilder.requestAndGet(): T =
		requestAndGet(object : TypeReference<T>() {})

	private fun HttpRequestBuilder.requestAndGetBytes(): Flow<ByteBuffer> =
		flow {
			request {
				val channel = it.bodyAsChannel()
				val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
				while (!channel.isClosedForRead) {
					val read = channel.readAvailable(buffer)
					if (read > 0) {
						emit(ByteBuffer.wrap(buffer.copyOfRange(0, read)))
					}
				}
			}
		}

//	private suspend fun jsonEventsLines(bodyChannel: ByteReadChannel): Flow<> = flow {
//		val asyncParser = objectMapper.createNonBlockingByteArrayParser()
//		val bufferArray = ByteArray(8192)
//		while (!bodyChannel.isClosedForRead) {
//			bodyChannel.readUTF8Line()?.also {
//				emit(asyncParser)
//			}
//		}
//	}

	override suspend fun create(q: Int?, n: Int?, requestId: String?): Boolean =
		prepareRequest(
			HttpMethod.Put,
			queryParameters = listOf(
				"q" to q?.toString(),
				"n" to n?.toString()
			),
			requestId = requestId
		).requestAndGetNull404<Map<String, *>?>()?.get("ok") == true

	override suspend fun security(security: Security): Boolean =
		prepareRequest(
			HttpMethod.Put,
			"_security",
			body = JsonBody(security)
		).requestAndGetNull404<Map<String, *>>()?.get("ok") == true

	override suspend fun designDocumentsIds(): Set<String> =
		prepareRequest(
			HttpMethod.Get,
			"_design_docs"
		).requestAndGetNull404<DesignDocumentResult>()
			?.rows
			?.mapNotNull { it.key }
			?.toSet()
			.orEmpty()

	override suspend fun exists(): Boolean =
		prepareRequest(
			HttpMethod.Get
		).requestAndGetNull404<Map<String, *>?>()?.get("db_name") != null

	override suspend fun membership(): Membership =
		prepareRequest(
			HttpMethod.Get,
			"_membership",
			includeDbPath = false
		).requestAndGet<Membership>()

	override suspend fun getConfigOption(nodeName: String, section: String, key: String): String? =
		prepareRequest(
			HttpMethod.Get,
			"_node",
			nodeName,
			"_config",
			section,
			key,
			includeDbPath = false
		).requestAndGetNull404<String>()

	override suspend fun setConfigOption(nodeName: String, section: String, key: String, newValue: String) {
		prepareRequest(
			HttpMethod.Put,
			"_node",
			nodeName,
			"_config",
			section,
			key,
			includeDbPath = false,
			body = TextBody(newValue, ContentType.Application.Json)
		).requestAndGet<String>()
	}

	override suspend fun deleteConfigOption(nodeName: String, section: String, key: String) {
		prepareRequest(
			HttpMethod.Delete,
			"_node",
			nodeName,
			"_config",
			section,
			key,
			includeDbPath = false,
		).requestAndGet<String>()
	}

	override suspend fun destroyDatabase(): Boolean =
		prepareRequest(
			HttpMethod.Delete,
		).requestAndGetNull404<Map<String, *>?>()?.get("ok") == true

	private fun prepareDocumentGetRequest(
		entityId: String,
		entityRev: String?,
		requestId: String?,
		options: Array<out Option>
	): HttpRequestBuilder {
		require(entityId.isNotBlank()) { "Entity id can't be blank" }
		require(entityRev?.isNotBlank() != false) { "Entity rev if provided can't be blank" }
		return prepareRequest(
			HttpMethod.Get,
			entityId,
			requestId = requestId,
			queryParameters = options.map {
				it.queryParamName to it.queryParamValue
			} + ("rev" to entityRev)
		)
	}

	@Deprecated("Use overload with TypeReference instead to avoid loss of Generic information in lists")
	override suspend fun <T : CouchDbDocument> get(id: String, clazz: Class<T>, vararg options: Option): T? =
		prepareDocumentGetRequest(
			id,
			null,
			null,
			options
		).requestAndGetNull404(clazz)

	override suspend fun <T : CouchDbDocument> get(id: String, type: TypeReference<T>, vararg options: Option): T? =
		prepareDocumentGetRequest(
			id,
			null,
			null,
			options
		).requestAndGetNull404(type)

	override suspend fun <T : CouchDbDocument> get(
		id: String,
		type: TypeReference<T>,
		requestId: String,
		vararg options: Option
	): T? =
		prepareDocumentGetRequest(
			id,
			null,
			requestId,
			options
		).requestAndGetNull404(type)

	@Deprecated("Use overload with TypeReference instead to avoid loss of Generic information in lists")
	override suspend fun <T : CouchDbDocument> get(
		id: String,
		rev: String,
		clazz: Class<T>,
		vararg options: Option
	): T? =
		prepareDocumentGetRequest(
			id,
			rev,
			null,
			options
		).requestAndGetNull404(clazz)

	override suspend fun <T : CouchDbDocument> get(
		id: String,
		rev: String,
		type: TypeReference<T>,
		vararg options: Option
	): T? =
		prepareDocumentGetRequest(
			id,
			rev,
			null,
			options
		).requestAndGetNull404(type)

	override suspend fun <T : CouchDbDocument> get(
		id: String,
		rev: String,
		type: TypeReference<T>,
		requestId: String,
		vararg options: Option
	): T? =
		prepareDocumentGetRequest(
			id,
			rev,
			requestId,
			options
		).requestAndGetNull404(type)

//
//	private data class AllDocsViewValue(val rev: String, val deleted: Boolean? = null)
//
//	override fun <T : CouchDbDocument> get(ids: Collection<String>, clazz: Class<T>, requestId: String?, onEntityException: EntityExceptionBehaviour): Flow<T> {
//		return getForPagination(ids, clazz, requestId, onEntityException)
//			.filterIsInstance<ViewRowWithDoc<String, AllDocsViewValue, T>>()
//			.map { it.doc }
//	}
//
//	override fun <T : CouchDbDocument> get(ids: Flow<String>, clazz: Class<T>, requestId: String?, onEntityException: EntityExceptionBehaviour): Flow<T> {
//		return getForPagination(ids, clazz, requestId, onEntityException)
//			.filterIsInstance<ViewRowWithDoc<String, AllDocsViewValue, T>>()
//			.map { it.doc }
//	}
//
//	override fun <T : CouchDbDocument> getForPagination(
//		ids: Collection<String>,
//		clazz: Class<T>,
//		requestId: String?,
//		onEntityException: EntityExceptionBehaviour
//	): Flow<ViewQueryResultEvent> {
//		val viewQuery = ViewQuery()
//			.allDocs()
//			.includeDocs(true)
//			.keys(ids)
//			.ignoreNotFound(true)
//		return queryView(viewQuery, String::class.java, AllDocsViewValue::class.java, clazz, requestId = requestId, onEntityException = onEntityException)
//	}
//
//	override fun <T : CouchDbDocument> getForPagination(
//		ids: Flow<String>,
//		clazz: Class<T>,
//		requestId: String?,
//		onEntityException: EntityExceptionBehaviour
//	): Flow<ViewQueryResultEvent> = flow {
//		ids.fold(Pair(persistentListOf<String>(), Triple(0, Integer.MAX_VALUE, 0L))) { acc, id ->
//			if (acc.first.size == 100) {
//				getForPagination(acc.first, clazz, requestId, onEntityException).fold(Pair(persistentListOf(id), acc.second)) { res, it ->
//					when (it) {
//						is ViewRowWithDoc<*, *, *> -> {
//							emit(it)
//							res
//						}
//						is TotalCount -> {
//							Pair(res.first, Triple(res.second.first + it.total, res.second.second, res.second.third))
//						}
//						is Offset -> {
//							Pair(
//								res.first,
//								Triple(res.second.first, min(res.second.second, it.offset), res.second.third)
//							)
//						}
//						is UpdateSequence -> {
//							Pair(res.first, Triple(res.second.first, res.second.second, max(res.second.third, it.seq)))
//						}
//						else -> res
//					}
//				}
//			} else {
//				Pair(acc.first.add(id), acc.second)
//			}
//		}.let { remainder ->
//			if (remainder.first.isNotEmpty())
//				getForPagination(remainder.first, clazz, onEntityException = onEntityException).fold(remainder.second) { counters, it ->
//					when (it) {
//						is ViewRowWithDoc<*, *, *> -> {
//							emit(it)
//							counters
//						}
//						is TotalCount -> {
//							Triple(counters.first + it.total, counters.second, counters.third)
//						}
//						is Offset -> {
//							Triple(counters.first, min(counters.second, it.offset), counters.third)
//						}
//						is UpdateSequence -> {
//							Triple(counters.first, counters.second, max(counters.third, it.seq))
//						}
//						else -> counters
//					}
//				} else remainder.second
//		}.let {
//			emit(TotalCount(it.first))
//			if (it.second < Integer.MAX_VALUE) {
//				emit(Offset(it.second))
//			}
//			if (it.third > 0L) {
//				emit(UpdateSequence(it.third))
//			}
//		}
//	}
//
	@ExperimentalCoroutinesApi
	override fun getAttachment(id: String, attachmentId: String, rev: String?, requestId: String?): Flow<ByteBuffer> {
		require(id.isNotBlank()) { "Id cannot be blank" }
		require(attachmentId.isNotBlank()) { "attachmentId cannot be blank" }
		return prepareRequest(
			HttpMethod.Get,
			id,
			attachmentId,
			queryParameters = listOf("rev" to rev)
		).requestAndGetBytes()
	}

	override suspend fun deleteAttachment(id: String, attachmentId: String, rev: String, requestId: String?): String {
		require(id.isNotBlank()) { "Id cannot be blank" }
		require(attachmentId.isNotBlank()) { "attachmentId cannot be blank" }
		require(rev.isNotBlank()) { "rev cannot be blank" }
		return prepareRequest(
			HttpMethod.Delete,
			id,
			attachmentId,
			queryParameters = listOf("rev" to rev)
		).requestAndGet<AttachmentResult>().rev
	}

	override suspend fun createAttachment(
		id: String,
		attachmentId: String,
		rev: String,
		contentType: String,
		data: Flow<ByteBuffer>,
		requestId: String?
	): String {
		require(id.isNotBlank()) { "Id cannot be blank" }
		require(attachmentId.isNotBlank()) { "attachmentId cannot be blank" }
		require(rev.isNotBlank()) { "rev cannot be blank" }
		return prepareRequest(
			HttpMethod.Put,
			id,
			attachmentId,
			queryParameters = listOf("rev" to rev),
			body = DataStreamBody(data, ContentType.parse(contentType))
		).requestAndGet<AttachmentResult>().rev
	}

	// CouchDB Response body for Create/Update/Delete
	private data class CUDResponse(
		val id: String?,
		val rev: String?,
		val ok: Boolean?,
		val error: String?,
		val reason: String?
	)

	override suspend fun <T : CouchDbDocument> create(entity: T, clazz: Class<T>, requestId: String?): T =
		prepareRequest(
			HttpMethod.Post,
			requestId = requestId,
			body = JsonBody(entity, clazz)
		).requestAndGet<CUDResponse>().let {
			validate(it)
			@Suppress("UNCHECKED_CAST")
			entity.withIdRev(it.id, it.rev!!) as T
		}

	private fun validate(response: CUDResponse) {
		response.error?.let { throw java.lang.IllegalStateException("${it}: ${response.reason ?: "-"}") }
		check(response.ok ?: false)
		checkNotNull(response.id)
		checkNotNull(response.rev)
	}

	@Suppress("UNCHECKED_CAST")
	override suspend fun <T : CouchDbDocument> update(entity: T, clazz: Class<T>, requestId: String?): T {
		require(entity.id.isNotBlank()) { "Id cannot be blank" }
		if (strictMode) {
			require(entity.rev != null) { "rev cannot be null"}
			require(entity.rev!!.isNotBlank()) { "rev cannot be blank"}
			require(entity.rev!!.matches(Regex("^[0-9]+-[a-z0-9]+$"))) { "Invalid rev format" }
		} else {
			if (entity.rev.isNullOrBlank()) {
				log.warn("Try to update {} of class {} with null or blank revision", entity.id, clazz)
			}
		}
		return prepareRequest(
			HttpMethod.Put,
			entity.id,
			requestId = requestId,
			body = JsonBody(entity, clazz)
		).requestAndGet<CUDResponse>().let {
			validate(it)
			entity.withIdRev(it.id, it.rev!!) as T
		}
	}

	override suspend fun <T : CouchDbDocument> delete(entity: T, requestId: String?): DocIdentifier {
		require(entity.id.isNotBlank()) { "Id cannot be blank" }
		require(!entity.rev.isNullOrBlank()) { "Revision cannot be blank" }
		return prepareRequest(
			HttpMethod.Delete,
			entity.id,
			queryParameters = listOf("rev" to entity.rev!!),
			requestId = requestId
		).requestAndGet<CUDResponse>().let {
			validate(it)
			DocIdentifier(it.id, it.rev)
		}
	}
//
//	override fun <T : CouchDbDocument> bulkUpdate(
//		entities: Collection<T>,
//		clazz: Class<T>,
//		requestId: String?
//	): Flow<BulkUpdateResult> =
//		flow {
//			coroutineScope {
//				entities.forEach {
//					require(it.rev == null || it.rev!!.matches(Regex("^[0-9]+-[a-z0-9]+$"))) { "Rev should be null or have a valid format" }
//				}
//				emitUpdateResults(this, BulkUpdateRequest(entities), requestId)
//			}
//		}
//
//	override fun <T : CouchDbDocument> bulkDelete(entities: Collection<T>, requestId: String?): Flow<BulkUpdateResult> =
//		bulkDeleteByIdAndRev(
//			entities.map { IdAndRev(it.id, it.rev) },
//			requestId
//		)
//
//	override fun bulkDeleteByIdAndRev(
//		entities: Collection<IdAndRev>,
//		requestId: String?
//	): Flow<BulkUpdateResult> = flow {
//		coroutineScope {
//			emitUpdateResults(this, BulkDeleteRequest(entities.map { DeleteRequest(it.id, it.rev) }), requestId)
//		}
//	}
//
//	private suspend fun FlowCollector<BulkUpdateResult>.emitUpdateResults(
//		scope: CoroutineScope,
//		requestBody: Any,
//		requestId: String?
//	) {
//		val uri = dbURI.addSinglePathComponent("_bulk_docs")
//
//		val request = newRequest(uri, objectMapper.writeValueAsString(requestBody), requestId = requestId)
//
//		val asyncParser = objectMapper.createNonBlockingByteArrayParser()
//		val jsonEvents = request.retrieveAndInjectRequestId(headerHandlers, timingHandler).toJsonEvents(asyncParser).produceIn(scope)
//		check(jsonEvents.receive() == StartArray) { "Expected result to start with StartArray" }
//		while (true) { // Loop through result array
//			val nextValue = jsonEvents.nextValue(asyncParser) ?: break
//
//			val bulkUpdateResult =
//				checkNotNull(nextValue.asParser(objectMapper).readValueAs(BulkUpdateResult::class.java))
//			emit(bulkUpdateResult)
//		}
//		jsonEvents.cancel()
//	}
//
//	override fun <T> mangoQuery(query: MangoQuery<T>, docType: Class<T>): Flow<ViewQueryResultEvent> = flow {
//		coroutineScope {
//			val request =
//				newRequest(query.generateQueryUrlFrom(dbURI.toString()), objectMapper.writeValueAsString(query))
//			val asyncParser = objectMapper.createNonBlockingByteArrayParser()
//
//			/** Execute the request and get the response as a Flow of [JsonEvent] **/
//			/** Execute the request and get the response as a Flow of [JsonEvent] **/
//			/** Execute the request and get the response as a Flow of [JsonEvent] **/
//
//			/** Execute the request and get the response as a Flow of [JsonEvent] **/
//			val jsonEvents =
//				request.retrieveAndInjectRequestId(headerHandlers, timingHandler).toJsonEvents(asyncParser).produceIn(this)
//
//			// Response should be a Json object
//			val firstEvent = jsonEvents.receive()
//			check(firstEvent == StartObject) { "Expected data to start with an Object" }
//			resultLoop@ while (true) { // Loop through result object fields
//				when (val nextEvent = jsonEvents.receive()) {
//					EndObject -> break@resultLoop // End of result object
//					is FieldName -> {
//						when (nextEvent.name) {
//							DOCS_NAME -> { // We found the "rows" field
//								// Rows field should be an array
//								check(jsonEvents.receive() == StartArray) { "Expected rows field to be an array" }
//								// At this point we are in the rows array, and StartArray event has been consumed
//
//								while (jsonEvents.nextValue(asyncParser)?.let {
//										val doc = it.asParser(objectMapper).readValueAs(docType)
//										emit(MangoQueryResult(doc, null))
//									} != null) { // Loop through doc objects
//								}
//							}
//							BOOKMARK_NAME -> {
//								emit(MangoQueryResult(null, jsonEvents.nextSingleValueAs<StringValue>().value))
//							}
//							ERROR_NAME -> {
//								val error = jsonEvents.nextSingleValueAs<StringValue>().value
//								jsonEvents.receive()
//								val reason = jsonEvents.nextSingleValueAs<StringValue>().value
//								throw MangoResultException(error, reason)
//							}
//							else -> jsonEvents.skipValue()
//						}
//					}
//					else -> println("Expected EndObject or FieldName, found $nextEvent")
//				}
//			}
//			jsonEvents.cancel()
//		}
//	}
//
//	override fun <K, V, T> queryView(
//		query: ViewQuery,
//		keyType: Class<K>,
//		valueType: Class<V>,
//		docType: Class<T>,
//		timeoutDuration: Duration?,
//		requestId: String?,
//		onEntityException: EntityExceptionBehaviour
//	): Flow<ViewQueryResultEvent> = flow {
//		coroutineScope {
//			val start = System.currentTimeMillis()
//
//			val dbQuery = query.dbPath(dbURI.toString())
//			val request = buildRequest(dbQuery, timeoutDuration, requestId)
//
//			val asyncParser = objectMapper.createNonBlockingByteArrayParser()
//
//			/** Execute the request and get the response as a Flow of [JsonEvent] **/
//			/** Execute the request and get the response as a Flow of [JsonEvent] **/
//			/** Execute the request and get the response as a Flow of [JsonEvent] **/
//
//			/** Execute the request and get the response as a Flow of [JsonEvent] **/
//			val jsonEvents =
//				request.retrieveAndInjectRequestId(headerHandlers, timingHandler).onStatus(404) {
//					if (query.skipIfViewDoesNotExist) {
//						//TODO accept to return null
//						throw SkippedQueryException()
//					} else {
//						throw IllegalArgumentException(it.responseBodyAsString())
//					}
//				}.toJsonEvents(asyncParser).produceIn(this)
//
//			// Response should be a Json object
//			val firstEvent = jsonEvents.receive()
//			check(firstEvent == StartObject) { "Expected data to start with an Object" }
//			resultLoop@ while (true) { // Loop through result object fields
//				when (val nextEvent = jsonEvents.receive()) {
//					EndObject -> break@resultLoop // End of result object
//					is FieldName -> {
//						when (nextEvent.name) {
//							ROWS_FIELD_NAME -> { // We found the "rows" field
//								// Rows field should be an array
//								check(jsonEvents.receive() == StartArray) { "Expected rows field to be an array" }
//								// At this point we are in the rows array, and StartArray event has been consumed
//								// We iterate over the rows until we encounter the EndArray event
//								rowsLoop@ while (true) { // Loop through "rows" array
//									when (jsonEvents.receive()) {
//										StartObject -> {} // Start of a new row
//										EndArray -> break@rowsLoop  // End of rows array
//										else -> error("Expected Start of new row or end of row array")
//									}
//									// At this point we are in a row object, and StartObject event has been consumed.
//									// We iterate over the field names and construct the ViewRowWithDoc or ViewRowNoDoc Object,
//									// until we encounter the EndObject event
//									var id: String? = null
//									var key: K? = null
//									var value: V? = null
//									var doc: T? = null
//									var decodeException: Exception? = null
//									rowLoop@ while (true) { // Loop through row object fields
//										when (val nextRowEvent = jsonEvents.receive()) {
//											EndObject -> break@rowLoop // End of row object
//											is FieldName -> {
//												val name = nextRowEvent.name
//												try {
//													when (name) {
//														// Parse doc id
//														ID_FIELD_NAME -> {
//															id = (jsonEvents.receive() as? StringValue)?.value
//																?: error("id field should be a string")
//														}
//														// Parse key
//														KEY_FIELD_NAME -> {
//															val keyEvents = jsonEvents.nextValue(asyncParser)
//																?: throw IllegalStateException("Invalid json expecting key")
//															key = keyEvents.asParser(objectMapper).readValueAs(keyType)
//														}
//														// Parse value
//														VALUE_FIELD_NAME -> {
//															val valueEvents = jsonEvents.nextValue(asyncParser)
//																?: throw IllegalStateException("Invalid json field name")
//															value = valueEvents.asParser(objectMapper)
//																.readValueAs(valueType)
//														}
//														// Parse doc
//														INCLUDED_DOC_FIELD_NAME -> {
//															if (dbQuery.isIncludeDocs) {
//																jsonEvents.nextValue(asyncParser)?.let {
//																	doc = try {
//																		it.asParser(objectMapper).readValueAs(docType)
//																	} catch (e: Exception) {
//																		if (onEntityException == EntityExceptionBehaviour.Fail) {
//																			throw e
//																		} else {
//																			decodeException = e
//																			null
//																		}
//																	}
//																}
//															}
//														}
//														// Error field
//														ERROR_FIELD_NAME -> {
//															val error = jsonEvents.nextSingleValueAs<StringValue>()
//															val errorMessage = error.value
//															if (!ignoreError(dbQuery, errorMessage)) {
//																// TODO retrieve key?
//																throw ViewResultException(null, errorMessage)
//															}
//														}
//														// Skip other fields values
//														else -> jsonEvents.skipValue()
//													}
//												} catch (e: InvalidFormatException) {
//													throw IllegalArgumentException(
//														"Cannot deserialize item with id: ${id ?: "N/A"}, error in $name",
//														e
//													)
//												}
//											}
//											else -> error("Expected EndObject or FieldName")
//										}
//									}
//									// We finished parsing a row, emit the result
//									id?.let {
//										val row: ViewRow<K, V, T> = if (dbQuery.isIncludeDocs) {
//											@Suppress("UNCHECKED_CAST")
//											when {
//												doc != null -> ViewRowWithDoc(it, key, value, doc) as ViewRow<K, V, T>
//												doc == null && decodeException != null -> ViewRowWithMalformedDoc(it, key, value, decodeException)
//												else -> ViewRowWithMissingDoc(it, key, value)
//											}
//										} else {
//											ViewRowNoDoc(it, key, value)
//										}
//										emit(row)
//									} ?: emit(ViewRowNoDoc("", key, value))
//
//								}
//							}
//							TOTAL_ROWS_FIELD_NAME -> {
//								val totalValue = jsonEvents.nextSingleValueAs<NumberValue<*>>().value
//								emit(TotalCount(totalValue.toInt()))
//							}
//							OFFSET_FIELD_NAME -> {
//								val offsetValue = jsonEvents.nextSingleValueAsOrNull<NumberValue<*>>()?.value ?: -1
//								emit(Offset(offsetValue.toInt()))
//							}
//							UPDATE_SEQUENCE_NAME -> {
//								val offsetValue = jsonEvents.nextSingleValueAs<NumberValue<*>>().value
//								emit(UpdateSequence(offsetValue.toLong()))
//							}
//							ERROR_FIELD_NAME -> {
//								error("Error executing request $request: ${jsonEvents.nextSingleValueAs<StringValue>().value}")
//							}
//							else -> jsonEvents.skipValue()
//						}
//					}
//					else -> error("Expected EndObject or FieldName, found $nextEvent")
//				}
//			}
//			jsonEvents.cancel()
//
//			log.debug("Request {} : timing {} ms", request, System.currentTimeMillis() - start)
//		}
//	}.catch { e -> if (e !is SkippedQueryException) { throw e } }
//
//	override fun <T : CouchDbDocument> subscribeForChanges(
//		clazz: Class<T>,
//		classDiscriminator: String,
//		classProvider: (String) -> Class<T>?,
//		since: String,
//		initialBackOffDelay: Long,
//		backOffFactor: Int,
//		maxDelay: Long,
//		heartBeatCallback: () -> Unit
//	): Flow<Change<T>> = channelFlow {
//		var lastSeq = since
//		var retriesWithoutSeqUpdate = 0
//		var delayMillis = initialBackOffDelay
//		var latestHeartbeat = Instant.now().toEpochMilli()
//
//		while (true) {
//			try {
//				val collectDeferred = async(Dispatchers.IO) {
//					internalSubscribeForChanges(clazz, lastSeq, classDiscriminator, classProvider) {
//						latestHeartbeat = Instant.now().toEpochMilli()
//						heartBeatCallback()
//					}.cancellable().catch { e ->
//						log.warn("Error detected while listening for changes", e)
//					}.collect { change ->
//						lastSeq = change.seq
//						retriesWithoutSeqUpdate = 0
//						delayMillis = initialBackOffDelay
//						send(change)
//					}
//				}
//
//				val watcher = async(Dispatchers.IO) {
//					var guard = true
//					while (guard) {
//						delay(32000)
//						if (latestHeartbeat < Instant.now().toEpochMilli() - 28000) {
//							guard = false
//							collectDeferred.cancel(NoHeartbeatException("No Heartbeat for 30 s."))
//						}
//					}
//				}
//
//				collectDeferred.await()
//
//				log.error("End of connection reached while listening for changes on ${dbURI}. Will try to re-subscribe in ${delayMillis}ms")
//				watcher.cancel()
//				delay(delayMillis)
//				log.error("Resubscribing to $dbURI")
//
//				// Attempt to re-subscribe indefinitely, with an exponential backoff
//				delayMillis = (delayMillis * backOffFactor).coerceAtMost(maxDelay)
//			} catch (e: NoHeartbeatException) {
//				if (retriesWithoutSeqUpdate >= 3) {
//					"No changes nor Heartbeat detected for multiple times with seq $lastSeq, aborting subscription. Corrupted change?.".let {
//						log.error(it, e)
//						throw IllegalStateException(it, e)
//					}
//				}
//				retriesWithoutSeqUpdate++
//				log.warn("No heartbeat cancellation $e caught while listening for changes on ${dbURI}. Will try to re-subscribe in ${delayMillis}ms")
//				log.warn("Resubscribing to $dbURI")
//			}
//		}
//	}
//
//	override suspend fun activeTasks(): List<ActiveTask> {
//		val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
//			dbURI.toString().removeSuffix(dbURI.path)
//		))
//			.addSinglePathComponent("_active_tasks")
//		val request = newRequest(uri)
//		return getCouchDbResponse(request)!!
//	}
//
//	override fun databaseInfos(ids: Flow<String>): Flow<DatabaseInfoWrapper> = flow {
//		val asyncParser = objectMapper.createNonBlockingByteArrayParser()
//		val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
//			dbURI.toString().removeSuffix(dbURI.path)
//		)).addSinglePathComponent("_dbs_info")
//
//		coroutineScope {
//			ids.bufferedChunks(20, 100).collect { dbIds ->
//				val request = newRequest(uri, objectMapper.writeValueAsString(mapOf("keys" to dbIds)), HttpMethod.POST)
//				val jsonEvents =
//					request.retrieveAndInjectRequestId(headerHandlers, timingHandler).toJsonEvents(asyncParser).produceIn(this)
//				val firstEvent = jsonEvents.receive()
//				check(firstEvent == StartArray) { "Expected data to start with an Array" }
//				while (jsonEvents.nextValue(asyncParser)?.let {
//						emit(it.asParser(objectMapper).readValueAs(DatabaseInfoWrapper::class.java))
//					} != null) { // Loop through doc objects
//				}
//			}
//		}
//	}
//
//	override fun allDatabases(): Flow<String> = flow {
//		val asyncParser = objectMapper.createNonBlockingByteArrayParser()
//		val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
//			dbURI.toString().removeSuffix(dbURI.path)
//		)).addSinglePathComponent("_all_dbs")
//
//		coroutineScope {
//			val request = newRequest(uri)
//			val jsonEvents =
//				request.retrieveAndInjectRequestId(headerHandlers, timingHandler).toJsonEvents(asyncParser).produceIn(this)
//			val firstEvent = jsonEvents.receive()
//			check(firstEvent == StartArray) { "Expected data to start with an Array" }
//			while (jsonEvents.nextValue(asyncParser)?.let {
//					emit(it.asParser(objectMapper).nextTextValue())
//				} != null) { // Loop through doc objects
//			}
//		}
//	}
//
//	override suspend fun schedulerDocs(): Scheduler.Docs {
//		val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
//			dbURI.toString().removeSuffix(dbURI.path)
//		)).addSinglePathComponent("_scheduler")
//			.addSinglePathComponent("docs")
//
//		val request = newRequest(uri)
//
//		return getCouchDbResponse(request) ?: Scheduler.Docs(0, 0, listOf())
//	}
//
//	override suspend fun schedulerJobs(): Scheduler.Jobs {
//		val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
//			dbURI.toString().removeSuffix(dbURI.path)
//		)).addSinglePathComponent("_scheduler")
//			.addSinglePathComponent("jobs")
//
//		val request = newRequest(uri)
//
//		return getCouchDbResponse(request) ?: Scheduler.Jobs(0, 0, listOf())
//	}
//
//	override suspend fun replicate(command: ReplicateCommand): ReplicatorResponse {
//		if (!checkReplicatorDB()) return ReplicatorResponse(
//			ok = false,
//			error = "Replicator DB not found",
//			reason = "Cannot fetch replicator DB or cannot create it",
//			id = command.id
//		)
//
//		val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
//			dbURI.toString().removeSuffix(dbURI.path)
//		)).addSinglePathComponent("_replicator")
//
//		val serializedCmd = objectMapper.writeValueAsString(command)
//		val request = newRequest(uri, HttpMethod.POST)
//			.header("Content-type", "application/json")
//			.body(serializedCmd)
//
//		return getCouchDbResponse(request)
//			?: ReplicatorResponse(ok = false, error = "404", reason = "replicate command returns null", id = command.id)
//	}
//
//	override suspend fun deleteReplication(docId: String): ReplicatorResponse {
//		val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
//			dbURI.toString().removeSuffix(dbURI.path)
//		))
//			.addSinglePathComponent("_replicator/")
//			.addSinglePathComponent("_purge")
//
//		return getReplicatorRevisions(docId)?.run {
//			val revisionList = revsInfo?.map { it["rev"]!! }
//			val body = mapOf(id to revisionList)
//
//			val serializedBody = objectMapper.writeValueAsString(body)
//			val request = newRequest(uri, HttpMethod.POST)
//				.header("Content-Type", "application/json")
//				.body(serializedBody)
//
//			request.getCouchDbResponse<Map<String, *>?>(true)?.get("purged")?.let {
//				val purged = it as Map<*, *>
//				if (purged.keys.contains(docId)) {
//					ReplicatorResponse(ok = true, id = docId)
//				} else {
//					ReplicatorResponse(
//						ok = false,
//						error = "Purge failure",
//						reason = "Id doesn't exist in purged list",
//						id = docId
//					)
//				}
//			} ?: ReplicatorResponse(ok = false, error = "404", reason = "delete command returns null", id = docId)
//		} ?: ReplicatorResponse(
//			ok = false,
//			error = "Document not found",
//			reason = "document with id $docId doesn't exist",
//			id = docId
//		)
//	}
//
//	private suspend fun getReplicatorRevisions(docId: String): ReplicatorDocument? {
//		val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
//			dbURI.toString().removeSuffix(dbURI.path)
//		))
//			.addSinglePathComponent("_replicator/")
//			.addSinglePathComponent(docId)
//			.param("revs_info", "true")
//
//		val request = newRequest(uri)
//
//		return getCouchDbResponse(request)
//	}
//
//	private suspend fun checkReplicatorDB(): Boolean {
//		val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
//			dbURI.toString().removeSuffix(dbURI.path)
//		))
//			.addSinglePathComponent("_replicator")
//
//		val request = newRequest(uri)
//
//		return request.getCouchDbResponse<Map<String, *>?>(true)?.run { true }
//			?: kotlin.run {
//				val createRequest = newRequest(uri, HttpMethod.PUT)
//				createRequest.getCouchDbResponse<Map<String, *>?>(true)?.run { get("ok") == true } ?: false
//			}
//	}
//
//	override suspend fun getCouchDBVersion(): String {
//		val uri = (dbURI.takeIf { it.path.isEmpty() || it.path == "/" } ?: java.net.URI.create(
//			dbURI.toString().removeSuffix(dbURI.path)
//		))
//
//		val request = newRequest(uri)
//
//		val response = request.getCouchDbResponse<Map<String, *>?>(true)
//
//		return response?.get("version").toString()
//	}
//
//	private suspend inline fun <reified T> getCouchDbResponse(request: Request): T? {
//		return request.getCouchDbResponse(object : TypeReference<T>() {}, nullIf404 = true)
//	}
//
//	@OptIn(FlowPreview::class)
//	private fun <T : CouchDbDocument> internalSubscribeForChanges(
//		clazz: Class<T>,
//		since: String,
//		classDiscriminator: String,
//		classProvider: (String) -> Class<T>?,
//		heartBeatCallback: () -> Unit
//	): Flow<Change<T>> = flow {
//		val charset = Charset.forName("UTF-8")
//
//		log.info("Subscribing for changes of class $clazz")
//		val asyncParser = objectMapper.createNonBlockingByteArrayParser()
//		// Construct request
//		val changesRequest = newRequest(
//			dbURI.addSinglePathComponent("_changes").param("feed", "continuous")
//				.param("heartbeat", "10000")
//				.param("include_docs", "true")
//				.param("since", since)
//		)
//
//		// Get the response as a Flow of CharBuffers (needed to split by line)
//		val responseText = changesRequest.retrieveAndInjectRequestId(headerHandlers, timingHandler).toTextFlow()
//		// Split by line
//		val splitByLine = responseText.split('\n') { heartBeatCallback() }
//		// Convert to json events
//		val jsonEvents = splitByLine.map { ev ->
//			ev.map {
//				charset.encode(it)
//			}.toJsonEvents(asyncParser)
//		}
//		// Parse as generic Change Object
//		val changes = jsonEvents.map { events ->
//			TokenBuffer(asyncParser).let { tb ->
//				var level = 0
//				val type = events.foldIndexed(null as String?) { index, type, jsonEvent ->
//					tb.copyFromJsonEvent(jsonEvent)
//
//					when (jsonEvent) {
//						is FieldName -> if (level == 2 && jsonEvent.name == classDiscriminator && index + 1 < events.size) (events[index + 1] as? StringValue)?.value else type
//						is StartArray -> type.also { level++ }
//						is StartObject -> type.also { level++ }
//						is EndObject -> type.also { level-- }
//						is EndArray -> type.also { level-- }
//						else -> type
//					}
//				}
//				Pair(type, tb)
//			}
//		}
//		changes.collect { (className, buffer) ->
//			if (className != null) {
//				val changeClass = classProvider(className)
//				if (changeClass != null && clazz.isAssignableFrom(changeClass)) {
//					val value = try {
//						val changeType =
//							object : TypeToken<Change<T>>() {}.where(object : TypeParameter<T>() {}, changeClass).type
//						val typeRef = object : TypeReference<Change<T>>() {
//							override fun getType(): Type {
//								return changeType
//							}
//						}
//						// Parse as actual Change object with the correct class
//						buffer.asParser(objectMapper).readValueAs<Change<T>>(typeRef)
//					} catch (e: JsonMappingException) {
//						log.debug("$dbURI Unmarshalling error while deserialising change of class $className", e)
//						null
//					}
//					value?.let { emit(it) }
//				}
//			}
//		}
//	}
//
//	private fun newRequest(
//		uri: java.net.URI,
//		method: HttpMethod = HttpMethod.Get,
//		timeoutDuration: Duration? = null,
//		requestId: String? = null
//	) =
//		httpClient.uri(uri)
//			.method(method, timeoutDuration)
//			.let {
//				val (username, password) = credentialsProvider()
//				it.basicAuth(username, password)
//			}
//			.let { requestId?.let { rid -> it.header("X-Couch-Request-ID", rid) } ?: it }
//
//	private fun newRequest(
//		uri: java.net.URI,
//		body: String,
//		method: HttpMethod = HttpMethod.Post,
//		requestId: String? = null
//	) =
//		newRequest(uri, method, requestId = requestId)
//			.header(HttpHeaderNames.CONTENT_TYPE.toString(), "application/json")
//			.body(body)
//
//	private fun buildRequest(query: ViewQuery, timeoutDuration: Duration? = null, requestId: String? = null) =
//		if (query.hasMultipleKeys()) {
//			newRequest(query.buildQuery(), query.keysAsJson(), requestId = requestId)
//		} else {
//			newRequest(query.buildQuery(), timeoutDuration = timeoutDuration, requestId = requestId)
//		}
//
//	private fun ignoreError(query: ViewQuery, error: String): Boolean {
//		return query.ignoreNotFound && NOT_FOUND_ERROR == error
//	}
//
//	@Deprecated("Use overload with TypeReference instead to avoid loss of Generic information in lists")
//	private suspend fun <T> Request.getCouchDbResponse(
//		clazz: Class<T>,
//		emptyResponseAsNull: Boolean = false,
//		nullIf404: Boolean = false
//	): T? {
//		return try {
//			@Suppress("DEPRECATION")
//			toFlow().toObject(clazz, objectMapper, emptyResponseAsNull)
//		} catch (ex: CouchDbException) {
//			if (ex.statusCode == 404 && nullIf404) null else throw ex
//		}
//	}
//
//	private suspend fun <T> Request.getCouchDbResponse(
//		type: TypeReference<T>,
//		emptyResponseAsNull: Boolean = false,
//		nullIf404: Boolean = false
//	): T? {
//		return try {
//			toFlow().toObject(type, objectMapper, emptyResponseAsNull)
//		} catch (ex: CouchDbException) {
//			if (ex.statusCode == 404 && nullIf404) null else throw ex
//		}
//	}
//
//	private fun Request.toFlow() = this
//		.retrieveAndInjectRequestId(headerHandlers, timingHandler)
//		.registerStatusMappers()
//		.toFlow()
//
//	private fun Response.registerStatusMappers() =
//		onStatus(403) { response ->
//			throw CouchDbException(
//				"Unauthorized Access",
//				response.statusCode,
//				response.responseBodyAsString(),
//				couchDbRequestId = response.headers.find { it.key == "X-Couch-Request-ID" }?.value,
//				couchDbBodyTime = response.headers.find { it.key == "X-Couchdb-Body-Time" }?.value?.toLong()
//			)
//		}
//			.onStatus(404) { response ->
//				throw CouchDbException(
//					"Document not found",
//					response.statusCode,
//					response.responseBodyAsString(),
//					couchDbRequestId = response.headers.find { it.key == "X-Couch-Request-ID" }?.value,
//					couchDbBodyTime = response.headers.find { it.key == "X-Couchdb-Body-Time" }?.value?.toLong()
//				)
//			}
//			.onStatus(409) { response ->
//				throw CouchDbConflictException(
//					"Document update Conflict",
//					response.statusCode,
//					response.responseBodyAsString(),
//					couchDbRequestId = response.headers.find { it.key == "X-Couch-Request-ID" }?.value,
//					couchDbBodyTime = response.headers.find { it.key == "X-Couchdb-Body-Time" }?.value?.toLong()
//				)
//			}
//
//	private suspend inline fun <reified T> Request.getCouchDbResponse(nullIf404: Boolean = false): T? =
//		getCouchDbResponse(object : TypeReference<T>() {}, null is T, nullIf404)
}

