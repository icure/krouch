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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.icure.asyncjacksonhttpclient.net.web.HttpMethod
import io.icure.asyncjacksonhttpclient.netty.NettyWebClient
import io.icure.asyncjacksonhttpclient.parser.EndArray
import io.icure.asyncjacksonhttpclient.parser.StartArray
import io.icure.asyncjacksonhttpclient.parser.StartObject
import io.icure.asyncjacksonhttpclient.parser.split
import io.icure.asyncjacksonhttpclient.parser.toJsonEvents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.taktik.couchdb.dao.CodeDAO
import org.taktik.couchdb.entity.*
import org.taktik.couchdb.exception.CouchDbConflictException
import org.taktik.couchdb.exception.CouchDbException
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.random.Random

@FlowPreview
@ExperimentalCoroutinesApi
class CouchDbClientTests {
    private val log = org.slf4j.LoggerFactory.getLogger(this::class.java)

    private val databaseHost =  System.getProperty("krouch.test.couchdb.server.url", "http://localhost:5984")
    private val databaseName =  System.getProperty("krouch.test.couchdb.database.name", "krouch-test")
    private val userName = System.getProperty("krouch.test.couchdb.username", "admin")
    private val password = System.getProperty("krouch.test.couchdb.password", "password")

    private val testResponseAsString = URL("https://jsonplaceholder.typicode.com/posts").openStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
    private val httpClient = NettyWebClient()
    private val client = ClientImpl(
        httpClient,
        URI(databaseHost),
        databaseName,
        userName,
        password,
        strictMode = true
    )

    private val testDAO = CodeDAO(client)

    @BeforeEach
    fun setupDatabase() {
        //  Setup the Database and DesignDocument (via the DAO interface)
        runBlocking {
            if(!client.exists()){
                client.create(8, 2)
            }
            try {
                testDAO.createOrUpdateDesignDocuments(true, false)
            } catch (e: Exception) {}
        }
    }

    @Test
    fun testSkipIfViewDoesNotExist() = runBlocking {
        val query = ViewQuery()
            .designDocId("_design/Code")
            .viewName("missing")
            .skipIfViewDoesNotExist(true)
            .includeDocs(true)
        println(query.toString())
        val res = client.queryViewIncludeDocs<String, String, Code>(query).toList().size
        assertEquals(0, res)
    }

    @Test
    fun testExceptionIsThrownByDefaultOnMalformedEntity() {
        runBlocking {
            val user = User(id = UUID.randomUUID().toString())
            val code = Code(id = UUID.randomUUID().toString())
            client.create(user)
            client.create(code)
            assertThrows(JsonMappingException::class.java) {
                runBlocking {
                    client.get<Code>(listOf(user.id, code.id)).toList()
                }
            }
        }
    }

    @Test
    fun testOnlyRelevantEntitiesAreReturnedOnMalformedEntityIfSpecified() {
        runBlocking {
            val user = User(id = UUID.randomUUID().toString())
            val code = Code(id = UUID.randomUUID().toString())
            client.create(user)
            client.create(code)
            val result = client.get<Code>(
                listOf(user.id, code.id),
                onEntityException = EntityExceptionBehaviour.Recover
            ).toList()

            assertEquals(1, result.size)
            assertEquals(code.id, result.first().id)
        }
    }

    @Test
    fun testExceptionIsNotThrownOnMalformedEntityIfSpecified() {
        runBlocking {
            val user = User(id = UUID.randomUUID().toString())
            val code = Code(id = UUID.randomUUID().toString())
            client.create(user)
            client.create(code)
            val result = client.getForPagination(
                listOf(user.id, code.id),
                Code::class.java,
                "test",
                EntityExceptionBehaviour.Recover
            ).toList()

            val codeResult = result.filterIsInstance<ViewRowWithDoc<String, Any, Code>>().first()
            assertEquals(code.id, codeResult.doc.id)

            val userResult = result.filterIsInstance<ViewRowWithMalformedDoc<String, Any>>().first()
            assertEquals(user.id, userResult.id)
            assertInstanceOf(JsonMappingException::class.java, userResult.error)
        }
    }

    @Test
    fun testSkipIfViewDoesNotExistFails() {
        val query = ViewQuery()
            .designDocId("_design/Code")
            .viewName("missing")
            .includeDocs(true)
        println(query.toString())
        assertThrows(java.lang.IllegalArgumentException::class.java) {
            runBlocking {
                client.queryViewIncludeDocs<String, String, Code>(
                    query
                ).toList()
            }
        }
    }

    @Test
    fun testSkipIfViewDoesNotExistFailsForOtherReason() {
        val query = ViewQuery()
            .designDocId("_design/Code")
            .viewName("all")
            .skipIfViewDoesNotExist(true)
            .startKey("Z")
            .endKey("A")
            .includeDocs(true)
        println(query.toString())
        assertThrows(java.lang.IllegalStateException::class.java) {
            runBlocking {
                client.queryViewIncludeDocs<String, String, Code>(
                    query
                ).toList()
            }
        }
    }

    @Test
    fun testSubscribeChanges() = runBlocking {
        val testSize = 10
        var testsDone = 0
        val deferredChanges = async {
            client.subscribeForChanges<Code>("java_type", {
                if (it == "Code") Code::class.java else null
            }).onEach {
                log.warn("Read code ${++testsDone}/$testSize")
            }.take(testSize).toList()
        }
        // Wait a bit before updating DB
        val codes = List(testSize) { Code.from("test", UUID.randomUUID().toString(), "test") }
        val createdCodes = codes.map {
            delay(300)
            client.create(it)
        }
        val changes = deferredChanges.await()
        assertEquals(createdCodes.size, changes.size)
        assertEquals(createdCodes.map { it.id }.toSet(), changes.map { it.id }.toSet())
        assertEquals(codes.map { it.code }.toSet(), changes.map { it.doc.code }.toSet())
    }

    @Test
    fun testSubscribeChangesHeartbeat() = runBlocking {
        val testSize = 1
        var testsDone = 0
        val deferredChanges = async {
            client.subscribeForChanges<Code>("java_type", {
                if (it == "Code") Code::class.java else null
            }).onEach {
                log.warn("Read code ${++testsDone}/$testSize")
            }.take(testSize).toList()
        }
        // Wait a bit before updating DB
        val codes = List(testSize) { Code.from("test", UUID.randomUUID().toString(), "test") }
        val createdCodes = codes.map {
            delay(45000)
            client.create(it)
        }
        val changes = withTimeout(50000) { deferredChanges.await() }
        assertEquals(createdCodes.size, changes.size)
        assertEquals(createdCodes.map { it.id }.toSet(), changes.map { it.id }.toSet())
        assertEquals(codes.map { it.code }.toSet(), changes.map { it.doc.code }.toSet())
    }

    @Test
    fun testSubscribeUserChanges() = runBlocking {
        val testSize = 100
        val deferredChanges = async {
            client.subscribeForChanges("java_type", {
                if (it == "User") {
                    User::class.java
                } else null
            }, "0").map { it.also {
                println("${it.doc.id}:${it.doc.login}")
            } }.take(testSize).toList()
        }

        val users = List(testSize) { User(UUID.randomUUID().toString()) }.map { client.create(it) }

        val changes = withTimeout(5000) { deferredChanges.await() }
        assertTrue(changes.isNotEmpty())
    }

    @Test
    fun testExists() = runBlocking {
        assertTrue(client.exists())
    }

    @Test
    fun testMembership() = runBlocking {
        val membership = client.membership()
        assertEquals(1, membership.allNodes.size)
        assertEquals("nonode@nohost", membership.allNodes.first())
        assertEquals(1, membership.clusterNodes.size)
        assertEquals("nonode@nohost", membership.clusterNodes.first())
    }

    @Test
    fun testRetrieveNonExistentConfigOption() = runBlocking {
        val value = client.getConfigOption("nonode@nohost", "unknown", "unknown")
        assertNull(value)
    }

    @Test
    fun testSetAndRetrieveConfigOption() = runBlocking {
        val newValue = "2"
        client.setConfigOption("nonode@nohost", "ken", "batch_channels", newValue)
        val retrievedValue = client.getConfigOption("nonode@nohost", "ken", "batch_channels")
        assertEquals(newValue, retrievedValue)
    }

    @Test
    fun testDestroyDatabase() = runBlocking {
        val client = ClientImpl(
            httpClient,
            URI(databaseHost),
            "icure-${UUID.randomUUID()}",
            userName,
            password
        )
        client.create(1,1)
        delay(1000L)
        assertTrue(client.destroyDatabase())
    }

    @Test
    fun testExists2() = runBlocking {
        val client = ClientImpl(
            httpClient,
            URI(databaseHost),
            UUID.randomUUID().toString(),
            userName,
            password
        )
        assertFalse(client.exists())
    }

    @Test
    fun testRequestGetResponseBytesFlow() = runBlocking {
        val bytesFlow = httpClient.uri("https://jsonplaceholder.typicode.com/posts").method(HttpMethod.GET).retrieve().toBytesFlow()

        val bytes = bytesFlow.fold(ByteBuffer.allocate(1000000), { acc, buffer -> acc.put(buffer) })
        bytes.flip()
        val responseAsString = StandardCharsets.UTF_8.decode(bytes).toString()
        assertEquals(testResponseAsString, responseAsString)
    }

    @Test
    fun testRequestGetText() = runBlocking {
        val charBuffers = httpClient.uri("https://jsonplaceholder.typicode.com/posts").method(HttpMethod.GET).retrieve().toTextFlow()
        val chars = charBuffers.toList().fold(CharBuffer.allocate(1000000), { acc, buffer -> acc.put(buffer) })
        chars.flip()
        assertEquals(testResponseAsString, chars.toString())
    }

    @Test
    fun testRequestGetTextAndSplit() = runBlocking {
        val charBuffers = httpClient.uri("https://jsonplaceholder.typicode.com/posts").method(HttpMethod.GET).retrieve().toTextFlow()
        val split = charBuffers.split('\n')
        val lines = split.map { it.fold(CharBuffer.allocate(100000), { acc, buffer -> acc.put(buffer) }).flip().toString() }.toList()
        assertEquals(testResponseAsString.split("\n"), lines)
    }

    @Test
    fun testRequestGetJsonEvent() = runBlocking {
        val asyncParser = ObjectMapper().also { it.registerModule(KotlinModule.Builder().build()) }.createNonBlockingByteArrayParser()

        val bytes = httpClient.uri("https://jsonplaceholder.typicode.com/posts").method(HttpMethod.GET).retrieve().toBytesFlow()
        val jsonEvents = bytes.toJsonEvents(asyncParser).toList()
        assertEquals(StartArray, jsonEvents.first(), "Should start with StartArray")
        assertEquals(StartObject, jsonEvents[1], "jsonEvents[1] == StartObject")
        assertEquals(EndArray, jsonEvents.last(), "Should end with EndArray")
    }

    @Test
    fun testClientQueryViewIncludeDocs() = runBlocking {
        val limit = 5
        val query = ViewQuery()
                .designDocId("_design/Code")
                .viewName("all")
                .limit(limit)
                .includeDocs(true)
        val flow = client.queryViewIncludeDocs<String, String, Code>(query)
        val codes = flow.toList()
        assertEquals(limit, codes.size)

        val otherQuery = ViewQuery()
            .designDocId("_design/Code-Aside")
            .viewName("by_type_aside")
            .limit(limit)
            .includeDocs(true)
        val otherFlow = client.queryViewIncludeDocs<List<String>, Int, Code>(otherQuery)
        val otherCodes = otherFlow.toList()
        assertEquals(limit, otherCodes.size)

    }

    @Test
    fun testClientQueryViewNoDocs() = runBlocking {
        val limit = 5
        val query = ViewQuery()
                .designDocId("_design/Code")
                .viewName("all")
                .limit(limit)
                .includeDocs(false)
        val flow = client.queryView<String, String>(query)
        val codes = flow.toList()
        assertEquals(limit, codes.size)
    }

    @Test
    fun testRawClientQuery() = runBlocking {
        val limit = 5
        val query = ViewQuery()
                .designDocId("_design/Code")
                .viewName("all")
                .limit(limit)
                .includeDocs(false)
        val flow = client.queryView(query, String::class.java, String::class.java, Nothing::class.java)

        val events = flow.toList()
        assertEquals(1, events.filterIsInstance<TotalCount>().size)
        assertEquals(1, events.filterIsInstance<Offset>().size)
        assertEquals(limit, events.filterIsInstance<ViewRow<*, *, *>>().size)
    }

    @Test
    fun testClientGetNonExisting() = runBlocking {
        val nonExistingId = UUID.randomUUID().toString()
        val code = client.get<Code>(nonExistingId)
        assertNull(code)
    }

    @Test
    fun testClientGetDbsInfo() = runBlocking {
        val dbs = client.databaseInfos(client.allDatabases()).toList()
        assertTrue(dbs.isNotEmpty())
    }

    @Test
    fun testClientAllDatabases() = runBlocking {
        val dbs = client.allDatabases().toList()
        assertTrue(dbs.isNotEmpty())
    }

    @Test
    fun testClientCreateAndGet() = runBlocking {
        val randomCode = UUID.randomUUID().toString()
        val toCreate = Code.from("test", randomCode, "test")
        val created = client.create(toCreate)
        assertEquals(randomCode, created.code)
        assertNotNull(created.id)
        assertNotNull(created.rev)
        val fetched = checkNotNull(client.get<Code>(created.id)) { "Code was just created, it should exist" }
        assertEquals(fetched.id, created.id)
        assertEquals(fetched.code, created.code)
        assertEquals(fetched.rev, created.rev)
    }

    @Test
    fun testClientUpdate() = runBlocking {
        val randomCode = UUID.randomUUID().toString()
        val toCreate = Code.from("test", randomCode, "test")
        val created = client.create(toCreate)
        assertEquals(randomCode, created.code)
        assertNotNull(created.id)
        assertNotNull(created.rev)
        // update code
        val anotherRandomCode = UUID.randomUUID().toString()
        val updated = client.update(created.copy(code = anotherRandomCode))
        assertEquals(created.id, updated.id)
        assertEquals(anotherRandomCode, updated.code)
        assertNotEquals(created.rev, updated.rev)
        val fetched = checkNotNull(client.get<Code>(updated.id))
        assertEquals(fetched.id, updated.id)
        assertEquals(fetched.code, updated.code)
        assertEquals(fetched.rev, updated.rev)
    }

    @Test
    fun testClientUpdateOutdated() {
        assertThrows(CouchDbConflictException::class.java) {
            runBlocking {
                val randomCode = UUID.randomUUID().toString()
                val toCreate = Code.from("test", randomCode, "test")
                val created = client.create(toCreate)
                assertEquals(randomCode, created.code)
                assertNotNull(created.id)
                assertNotNull(created.rev)
                // update code
                val anotherRandomCode = UUID.randomUUID().toString()
                val updated = client.update(created.copy(code = anotherRandomCode))
                assertEquals(created.id, updated.id)
                assertEquals(anotherRandomCode, updated.code)
                assertNotEquals(created.rev, updated.rev)
                val fetched = checkNotNull(client.get<Code>(updated.id))
                assertEquals(fetched.id, updated.id)
                assertEquals(fetched.code, updated.code)
                assertEquals(fetched.rev, updated.rev)
                // Should throw a Document update conflict Exception
                @Suppress("UNUSED_VARIABLE")
                val updateResult = client.update(created)
            }
        }
    }

    @Test
    fun testGetAllWithView() = runBlocking {
        val toCreate = Code.from("test", UUID.randomUUID().toString(), "test")
        val created = client.create(toCreate)
        val viewQuery = ViewQuery()
            .designDocId("_design/${Code::class.java.simpleName}")
            .viewName("all")
            .includeDocs(true)
            .reduce(false)
            .startKey(NullKey)
            .startDocId(created.id)
        val retrieved = client.queryViewIncludeDocs<Any?, String, Code>(viewQuery).toList()
        assert(retrieved.isNotEmpty())
        assertEquals(retrieved.first().id, created.id)
    }

    @Test
    fun testClientDelete() = runBlocking {
        val randomCode = UUID.randomUUID().toString()
        val toCreate = Code.from("test", randomCode, "test")
        val created = client.create(toCreate)
        assertEquals(randomCode, created.code)
        assertNotNull(created.id)
        assertNotNull(created.rev)
        val deletedRev = client.delete(created)
        assertNotEquals(created.rev, deletedRev)
        assertNull(client.get<Code>(created.id))
    }

    @Test
    fun testClientBulkGet() = runBlocking {
        val limit = 100
        val query = ViewQuery()
                .designDocId("_design/Code")
                .viewName("by_type")
                .limit(limit)
                .includeDocs(true)
        val flow = client.queryViewIncludeDocs<List<*>, Int, Code>(query)
        val codes = flow.map { it.doc }.toList()
        val codeIds = codes.map { it.id }
        val flow2 = client.get<Code>(codeIds)
        val codes2 = flow2.toList()
        assertEquals(codes, codes2)
    }

    @Test
    fun testClientBulkUpdate() = runBlocking {
        val testSize = 100
        val codes = List(testSize) { Code.from("test", UUID.randomUUID().toString(), "test") }
        val updateResult = client.bulkUpdate(codes).toList()
        assertEquals(testSize, updateResult.size)
        assertTrue(updateResult.all { it.error == null })
        val revisions = updateResult.map { checkNotNull(it.rev) }
        val ids = codes.map { it.id }
        val codeCodes = codes.map { it.code }
        val fetched = client.get<Code>(ids).toList()
        assertEquals(codeCodes, fetched.map { it.code })
        assertEquals(revisions, fetched.map { it.rev })
    }

    @Test
    fun testBasicDAOQuery() = runBlocking {
        val codes = testDAO.findCodeByTypeAndVersion("test", "test").map { it.doc }.toList()
        val fetched = client.get<Code>(codes.map { it.id }).toList()
        assertEquals(codes.map { it.code }, fetched.map { it.code })
    }

    @Test
    fun testReplicateCommands() = runBlocking {
        if (client.getCouchDBVersion() >= "3.2.0") {
            val oneTimeCmd = ReplicateCommand.oneTime(
                    sourceUrl = URI("${databaseHost}/${databaseName}"),
                    sourceUsername = userName,
                    sourcePassword = password,
                    targetUrl = URI("${databaseHost}/${databaseName}_one_time"),
                    targetUsername = userName,
                    targetPassword = password,
                    id = "${databaseName}_one_time"
            )

            val continuousCmd = ReplicateCommand.continuous(
                    sourceUrl = URI("${databaseHost}/${databaseName}"),
                    sourceUsername = userName,
                    sourcePassword = password,
                    targetUrl = URI("${databaseHost}/${databaseName}_continuous"),
                    targetUsername = userName,
                    targetPassword = password,
                    id = "${databaseName}_continuous"
            )
            val oneTimeResponse = client.replicate(oneTimeCmd)
            assertTrue(oneTimeResponse.ok)

            val continuousResponse = client.replicate(continuousCmd)
            assertTrue(continuousResponse.ok)

            val schedulerDocsResponse = client.schedulerDocs()
            assertTrue(schedulerDocsResponse.docs.size >= 2)

            val schedulerJobsResponse = client.schedulerJobs()
            assertTrue(schedulerJobsResponse.jobs.isNotEmpty())

            schedulerDocsResponse.docs
                    .filter { it.docId == oneTimeCmd.id || it.docId == continuousCmd.id }
                    .forEach {
                        val cancelResponse = client.deleteReplication(it.docId!!)
                        assertTrue(cancelResponse.ok)
                    }
        }
    }

    @Test
    fun testActiveTasksInstanceMapper() = runBlocking {
        val activeTasksSample = File(javaClass.classLoader.getResource("active_tasks_sample.json")!!.file)
                .readText()
                .replace("\n".toRegex(), "")
        val kotlinMapper = ObjectMapper().also { it.registerModule(KotlinModule.Builder().build()) }
        val activeTasks: List<ActiveTask> = kotlinMapper.readValue(activeTasksSample)

        assertTrue(activeTasks[0] is Indexer)
        assertTrue(activeTasks[1] is ViewCompactionTask)
        assertTrue(activeTasks[2] is DatabaseCompactionTask)
        assertTrue(activeTasks[4] is ReplicationTask)
    }

    @Test
    fun testUpdate() = runBlocking {
        val created = client.create(Code.from("test", UUID.randomUUID().toString(), "test"))
        val newId = UUID.randomUUID().toString()
        val updated = client.update(created.copy(code = newId))
        assertEquals(newId, updated.code)
    }

    @Test
    fun testUpdateWithEmptyRev(): Unit = runBlocking {
        val created = client.create(Code.from("test", UUID.randomUUID().toString(), "test"))
        val newId = UUID.randomUUID().toString()

        assertThrows<IllegalArgumentException> {
            client.update(created.copy(rev = "", code = newId))
        }
    }

    @Test
    fun testUpdateWithNullRev(): Unit = runBlocking {
        val created = client.create(Code.from("test", UUID.randomUUID().toString(), "test"))
        val newId = UUID.randomUUID().toString()

        assertThrows<IllegalArgumentException> {
            client.update(created.copy(rev = null, code = newId))
        }
    }

    @Test
    fun testUpdateWithNonValidRev(): Unit = runBlocking {
        val created = client.create(Code.from("test", UUID.randomUUID().toString(), "test"))
        val newId = UUID.randomUUID().toString()

        assertThrows<IllegalArgumentException> {
            client.update(created.copy(rev = UUID.randomUUID().toString(), code = newId))
        }
    }

    @Test
    fun testBulkUpdate(): Unit = runBlocking {
        val code1 = client.create(Code.from("test", UUID.randomUUID().toString(), "test"))
        val code2 = client.create(Code.from("test", UUID.randomUUID().toString(), "test"))
        val newId = UUID.randomUUID().toString()

        assertEquals(
            2,
            client.bulkUpdate(listOf(code1.copy(rev = null, code = newId), code2.copy(code = newId))).count()
        )

    }

    @Test
    fun testBulkUpdateWithEmptyRev(): Unit = runBlocking {
        val code1 = client.create(Code.from("test", UUID.randomUUID().toString(), "test"))
        val code2 = client.create(Code.from("test", UUID.randomUUID().toString(), "test"))
        val newId = UUID.randomUUID().toString()

        assertThrows<IllegalArgumentException> {
            client.bulkUpdate(listOf(code1.copy(code = newId), code2.copy(rev = "", code = newId))).collect()
        }
    }

    @Test
    fun testBulkUpdateWithNonValidRev(): Unit = runBlocking {
        val code1 = client.create(Code.from("test", UUID.randomUUID().toString(), "test"))
        val code2 = client.create(Code.from("test", UUID.randomUUID().toString(), "test"))
        val newId = UUID.randomUUID().toString()

        assertThrows<IllegalArgumentException> {
            client.bulkUpdate(listOf(code1.copy(code = newId), code2.copy(rev = UUID.randomUUID().toString(), code = newId))).collect()
        }
    }

    @Test
    fun testCreateAndGetAttachment() = runBlocking {
        val randomCode = UUID.randomUUID().toString()
        val created = client.create(Code.from("test", randomCode, "test"))
        val attachmentId = "attachment1"
        val attachment = byteArrayOf(1, 2, 3)
        client.createAttachment(
            created.id,
            attachmentId,
            created.rev!!,
            "application/json",
            flowOf(ByteBuffer.wrap(attachment))
        )
        val retrievedAttachment = ByteArrayOutputStream().use { os ->
            @Suppress("BlockingMethodInNonBlockingContext")
            client.getAttachment(created.id, attachmentId).collect { bb ->
                if (bb.hasArray() && bb.hasRemaining()) {
                    os.write(bb.array(), bb.position() + bb.arrayOffset(), bb.remaining())
                } else {
                    os.write(ByteArray(bb.remaining()).also { bb.get(it) })
                }
            }
            os.toByteArray()
        }
        assertEquals(retrievedAttachment.toList(), attachment.toList())
        try {
            client.getAttachment(created.id, "non-existing").first()
            fail("Should not be able to retrieve non-existing attachment")
        } catch (e: CouchDbException) {
            assertEquals(e.statusCode, 404)
        }
    }

    @Test
    fun testObsoleteViewIsNotIncludedInDesignDoc() = runBlocking {
        val dd = client.get<DesignDocument>("_design/${Code::class.java.simpleName}")
        assertNotNull(dd)
        assertNull(dd!!.views["all_obsolete"])
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ChangeTestEntityA(
        @JsonProperty("_id") override val id: String,
        val data: String,
        @JsonProperty("_rev") override val rev: String? = null,
        @JsonProperty("rev_history") override val revHistory: Map<String, String>? = null,
    ) : CouchDbDocument {
        var classname
            get() = this::class.qualifiedName
            set(value) { require(this::class.qualifiedName == value) }

        override fun withIdRev(id: String?, rev: String) = id?.let { copy(id = it, rev = rev) } ?: copy(rev = rev)
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ChangeTestEntityB(
        @JsonProperty("_id") override val id: String,
        val data: Int,
        @JsonProperty("_rev") override val rev: String? = null,
        @JsonProperty("rev_history") override val revHistory: Map<String, String>? = null,
    ) : CouchDbDocument {
        var classname
            get() = this::class.qualifiedName
            set(value) { require(this::class.qualifiedName == value) }

        override fun withIdRev(id: String?, rev: String) = id?.let { copy(id = it, rev = rev) } ?: copy(rev = rev)
    }

    @Test
    fun testGetChanges(): Unit = runBlocking {
        val client = ClientImpl(
            httpClient,
            URI(databaseHost),
            "changestestdb-${System.currentTimeMillis()}",
            userName,
            password,
            strictMode = true
        )
        client.create(q = 1, n = 1)
        val expectedResults = mutableListOf<ChangeTestEntityA>()
        client.create(ChangeTestEntityA(UUID.randomUUID().toString(), "1")).also { expectedResults += it }
        client.create(ChangeTestEntityB(UUID.randomUUID().toString(), 1))
        client.create(ChangeTestEntityA(UUID.randomUUID().toString(), "2")).also { expectedResults += it }
        client.create(ChangeTestEntityA(UUID.randomUUID().toString(), "3")).also { expectedResults += it }
        client.create(ChangeTestEntityB(UUID.randomUUID().toString(), 2))
        client.create(ChangeTestEntityA(UUID.randomUUID().toString(), "4")).also { expectedResults += it }
        client.create(ChangeTestEntityB(UUID.randomUUID().toString(), 3))
        client.create(ChangeTestEntityB(UUID.randomUUID().toString(), 4))
        client.create(ChangeTestEntityA(UUID.randomUUID().toString(), "5")).also { expectedResults += it }
        client.create(ChangeTestEntityB(UUID.randomUUID().toString(), 5))
        client.create(ChangeTestEntityA(UUID.randomUUID().toString(), "6")).also { expectedResults += it }
        client.create(ChangeTestEntityB(UUID.randomUUID().toString(), 6))
        client.create(ChangeTestEntityB(UUID.randomUUID().toString(), 7))
        client.create(ChangeTestEntityB(UUID.randomUUID().toString(), 8))
        client.getChanges<ChangeTestEntityA>("0", 100, "classname").apply {
            assertEquals(0, pending)
            assertEquals(expectedResults, results.map { it.doc })
            assertTrue(last_seq.startsWith("14-"))
        }
        val nextSince = client.getChanges<ChangeTestEntityA>("0", 6, "classname").run {
            assertEquals(8, pending)
            assertEquals(expectedResults.take(4), results.map { it.doc })
            assertTrue(last_seq.startsWith("6-"))
            last_seq
        }
        client.getChanges<ChangeTestEntityA>(nextSince, 6, "classname").run {
            // Not enough entities matching filter remaining to reach limit, consumes everything left
            assertEquals(0, pending)
            assertEquals(expectedResults.takeLast(2), results.map { it.doc })
            assertTrue(last_seq.startsWith("14-"))
            last_seq
        }
    }

    fun testAttachmentSize() = runBlocking {
        val created = client.create(Code.from("test", UUID.randomUUID().toString(), "test"))
        val sizes = listOf(10, 50, 100, 500, 1000, 1234).map { "attachment$it" to it }
        sizes.fold(created.rev!!) { rev, (id, size) ->
            val attachment = Random(System.currentTimeMillis()).nextBytes(size)
            client.createAttachment(
                created.id,
                id,
                rev,
                "application/json",
                flowOf(ByteBuffer.wrap(attachment))
            )
        }
        val codeWithAttachments = client.get<Code>(created.id)!!
        assertEquals(codeWithAttachments.attachments?.size, sizes.size)
        assertEquals(codeWithAttachments.attachments?.map { it.key to it.value.length?.toInt() }?.toSet(), sizes.toSet())
    }
}
