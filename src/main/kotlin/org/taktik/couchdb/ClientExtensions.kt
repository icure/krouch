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
import kotlinx.coroutines.flow.filterIsInstance
import org.taktik.couchdb.entity.Change
import org.taktik.couchdb.entity.ChangesChunk
import org.taktik.couchdb.entity.EntityExceptionBehaviour
import org.taktik.couchdb.entity.Option
import org.taktik.couchdb.entity.ViewQuery
import org.taktik.couchdb.mango.MangoQuery
import org.taktik.couchdb.queryresult.MangoQueryResult
import org.taktik.couchdb.queryresult.ViewRowNoDoc
import org.taktik.couchdb.queryresult.ViewRowWithDoc
import org.taktik.couchdb.response.BulkUpdateResult
import java.time.Duration

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

suspend inline fun <reified T : CouchDbDocument> Client.getWithQuorum(
	id: String,
	quorum: Int,
	requestId: String? = null,
	rev: String? = null,
	timeout: Duration? = null,
	vararg options: Option
): T? =
	this.getWithQuorum(id, object : TypeReference<T>() {}, quorum, requestId, rev, timeout, *options)

inline fun <reified T : CouchDbDocument> Client.get(ids: List<String>, onEntityException: EntityExceptionBehaviour = EntityExceptionBehaviour.Fail): Flow<T> = this.get(ids, T::class.java, null, onEntityException)

suspend inline fun <reified T : CouchDbDocument> Client.create(entity: T): T = this.create(entity, T::class.java)

suspend inline fun <reified T : CouchDbDocument> Client.createWithQuorum(entity: T, quorum: Int, timeout: Duration? = null, requestId: String? = null): Pair<T, Boolean> = this.createWithQuorum(entity, object : TypeReference<T>() {}, quorum, timeout, requestId)

suspend inline fun <reified T : CouchDbDocument> Client.update(entity: T): T = this.update(entity, T::class.java)

suspend inline fun <reified T : CouchDbDocument> Client.updateWithQuorum(entity: T, quorum: Int, timeout: Duration? = null, requestId: String? = null): Pair<T, Boolean> = this.updateWithQuorum(entity, object : TypeReference<T>() {}, quorum, timeout, requestId)

inline fun <reified T : CouchDbDocument> Client.bulkUpdate(entities: List<T>): Flow<BulkUpdateResult> =
	this.bulkUpdate(entities, T::class.java)

suspend inline fun <reified T : CouchDbDocument> Client.getChanges(
	since: String,
	limit: Int,
	classDiscriminator: String,
	discriminatorValue: String? = null
) = getChanges(
	object : TypeReference<ChangesChunk<T>>() {},
	since,
	limit,
	classDiscriminator,
	discriminatorValue ?: requireNotNull(T::class.qualifiedName) { "Class ${T::class} has no qualified name, provide discriminator value explicitly." }
)

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
