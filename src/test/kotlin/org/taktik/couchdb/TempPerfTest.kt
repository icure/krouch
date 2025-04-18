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

import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.async.ByteArrayFeeder
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.util.TokenBuffer
import com.fasterxml.jackson.module.kotlin.readValue
import io.icure.asyncjacksonhttpclient.net.web.HttpMethod
import io.icure.asyncjacksonhttpclient.net.web.Request
import io.icure.asyncjacksonhttpclient.net.web.WebClient
import io.icure.asyncjacksonhttpclient.netty.NettyRequest
import io.icure.asyncjacksonhttpclient.netty.NettyWebClient
import io.icure.asyncjacksonhttpclient.parser.toObject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.ChannelWriterContent
import io.ktor.http.content.WriterContent
import io.ktor.serialization.jackson.jackson
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.core.toByteArray
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.read
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeFully
import io.netty.handler.logging.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.InternalIoApi
import org.junit.jupiter.api.Test
import reactor.netty.resources.ConnectionProvider
import reactor.netty.transport.logging.AdvancedByteBufFormat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import kotlin.random.Random

class TempPerfTest {

//    // Takes 9.7s - okhttp
//    // Takes 12.5s - java
//    // 12.5 - apache
//    fun ByteReadChannel.toJsonTree(objectMapper: ObjectMapper) {
//        objectMapper.readValue<Map<String, *>>(toInputStream())
//    }
//
//    // Takes 9.7s
//    suspend fun ByteReadChannel.toJsonTree2(objectMapper: ObjectMapper) {
//        val parser = objectMapper.createNonBlockingByteArrayParser()
//        val buffer = TokenBuffer(parser)
//
//        fun parseInput() {
//            while (true) {
//                val token = parser.nextToken();
//                if (token == JsonToken.NOT_AVAILABLE) {
//                    break;
//                }
//                buffer.copyCurrentEvent(parser);
//            }
//        }
//
//        // Can reuse this byte array without issues
//        val arrayBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
//        val feeder = parser.nonBlockingInputFeeder as ByteArrayFeeder
//
//        while (!this.isClosedForRead) {
//            val written = this.readAvailable(arrayBuffer)
//            if (written > 0) {
//                feeder.feedInput(
//                    arrayBuffer,
//                    0,
//                    written
//                )
//            }
//            parseInput()
//        }
//        objectMapper.readValue<Map<String, *>>(buffer.asParser())
//    }

    // Takes 11 seconds
    suspend fun ByteReadChannel.toJsonTree3(objectMapper: ObjectMapper) {
        val parser = objectMapper.createNonBlockingByteArrayParser()
        val buffer = TokenBuffer(parser)

        fun parseInput() {
            while (true) {
                val token = parser.nextToken();
                if (token == JsonToken.NOT_AVAILABLE) {
                    break;
                }
                buffer.copyCurrentEvent(parser);
            }
        }

        // Can reuse this byte array without issues
        val feeder = parser.nonBlockingInputFeeder as ByteArrayFeeder

        while (!this.isClosedForRead) {
            this.read { bytes, start, endExclusive ->
                feeder.feedInput(
                    bytes,
                    start,
                    endExclusive
                )
                parseInput()
                endExclusive - start
            }
        }
        objectMapper.readValue<Map<String, *>>(buffer.asParser())
    }

    @Test
    fun try0(): Unit = runBlocking {
        val client = HttpClient {
            install(ContentNegotiation) {
                jackson(streamRequestBody = true)
            }
        }
        val objectMapper = ObjectMapper()
        val start = System.currentTimeMillis()
        repeat(5000) {
            client.prepareGet(
                "http://localhost:8080/giantJson"
            ).execute {
                it.body<Map<String, *>>()
            }
        }
        println("Done in ${System.currentTimeMillis() - start}")
    }

    @Test
    fun try1(): Unit = runBlocking {
        val client = HttpClient ()
        val objectMapper = ObjectMapper()
        val start = System.currentTimeMillis()
        repeat(5000) {
            client.prepareGet(
                "http://localhost:8080/giantJson"
            ).execute {
                it.bodyAsChannel().toJsonTree3(objectMapper)
            }
        }
        println("Done in ${System.currentTimeMillis() - start}")
    }

    @Test
    fun try2(): Unit = runBlocking {
        val httpClient = NettyWebClient()
        val uri = URI("http://localhost:8080/giantJson")
        val mapper = ObjectMapper()
        val start = System.currentTimeMillis()
        repeat(5000) {
            httpClient.uri(uri).method(HttpMethod.GET).retrieve().toFlow().toObject<Map<String, *>>(mapper, false)
        }
        println("Done in ${System.currentTimeMillis() - start}")
    }

    @Test
    fun tryParallel0(): Unit = runBlocking {
        val client = HttpClient {
            install(ContentNegotiation) {
                jackson(streamRequestBody = true)
            }
        }
        val objectMapper = ObjectMapper()
        val start = System.currentTimeMillis()
        repeat(50) { round ->
            val roundStart = System.currentTimeMillis()
            List(10000) { parallel ->
                async {
                    client.prepareGet(
                        "http://localhost:8080/normalJson"
                    ).execute {
                        it.body<Map<String, *>>()
                    }
                }
            }.awaitAll()
            println("Round $round in ${System.currentTimeMillis() - roundStart}")
        }
        println("Done in ${System.currentTimeMillis() - start}")
    }

    @Test
    fun tryParallel1(): Unit = runBlocking {
        val client = HttpClient ()
        val objectMapper = ObjectMapper()
        val start = System.currentTimeMillis()
        repeat(50) { round ->
            val roundStart = System.currentTimeMillis()
            List(10000) { parallel ->
                async {
                    client.prepareGet(
                        "http://localhost:8080/normalJson"
                    ).execute {
                        it.bodyAsChannel().toJsonTree3(objectMapper)
                    }
                }
            }.awaitAll()
            println("Round $round in ${System.currentTimeMillis() - roundStart}")
        }
        println("Done in ${System.currentTimeMillis() - start}")
    }

    @Test
    fun tryParallel2(): Unit = runBlocking {
        val connectionProvider = ConnectionProvider.builder("myConnectionPool")
            .pendingAcquireMaxCount(10000)
            .build()
        val httpClient = object : WebClient {
            override fun uri(uri: URI): Request {
                val client = reactor.netty.http.client.HttpClient.create(connectionProvider).wiretap("io.icure.asyncjacksonhttpclient.netty", LogLevel.DEBUG, AdvancedByteBufFormat.HEX_DUMP)
                return NettyRequest(client, uri)
            }
        }
        val uri = URI("http://localhost:8080/normalJson")
        val mapper = ObjectMapper()
        val start = System.currentTimeMillis()
        repeat(50) { round ->
            val roundStart = System.currentTimeMillis()
            List(10000) {
                async {
                    httpClient.uri(uri).method(HttpMethod.GET).retrieve().toFlow().toObject<Map<String, *>>(mapper, false)
                }
            }.awaitAll()
            println("Round $round in ${System.currentTimeMillis() - roundStart}")
        }
        println("Done in ${System.currentTimeMillis() - start}")
    }

    @Test
    fun testReusableByteArray(): Unit {
        val objectMapper = ObjectMapper()
        val parser = objectMapper.createNonBlockingByteArrayParser()
        val buffer = TokenBuffer(parser)

        fun parseInput() {
            while (true) {
                val token = parser.nextToken();
                if (token == JsonToken.NOT_AVAILABLE) {
                    break;
                }
                println("Got token: $token")
                buffer.copyCurrentEvent(parser);
            }
        }

        val arrayBuffer = ByteArray(4096)
        val feeder = parser.nonBlockingInputFeeder as ByteArrayFeeder
        listOf(
            "{\"x",
            "\":\"1234",
            "5678",
            "9098",
            "\"}"
        ).forEach { piece ->
            println("New piece: $piece")
            val pieceBytes = piece.toByteArray()
            pieceBytes.copyInto(arrayBuffer)
            feeder.feedInput(arrayBuffer, 0, pieceBytes.size)
            parseInput()
        }
        println("Done ${objectMapper.readTree<JsonNode>(buffer.asParser())}")
    }

    @Test
    fun testReusableByteArray2(): Unit {
        val objectMapper = ObjectMapper()
        val parser = objectMapper.createNonBlockingByteArrayParser()
        val buffer = TokenBuffer(parser)

        fun parseInput() {
            while (true) {
                val token = parser.nextToken();
                if (token == JsonToken.NOT_AVAILABLE) {
                    break;
                }
                println("Got token: $token")
                buffer.copyCurrentEvent(parser);
            }
        }

        val arrayBuffer = ByteArray(10_000)
        val fullData = Random.Default.nextBytes(1_000_000).toUByteArray().joinToString("") { it.toString(radix = 16).padStart(2, '0') }
        println("Size of data: " + fullData.length)
        val feeder = parser.nonBlockingInputFeeder as ByteArrayFeeder
        listOf(
            "{\"x\":\"",
            *fullData.chunked(10_000).toTypedArray(),
            "\"}"
        ).forEach { piece ->
            println("New piece: ${piece.take(100)}")
            val pieceBytes = piece.toByteArray()
            pieceBytes.copyInto(arrayBuffer)
            feeder.feedInput(arrayBuffer, 0, pieceBytes.size)
            parseInput()
        }
        println("Done: ${objectMapper.readTree<JsonNode>(buffer.asParser()).get("x").asText() == fullData} ")
    }

    @Test
    fun testCancellableInputStream(): Unit = runBlocking {
        val client = HttpClient ()
        val dataOut = ByteArray(4096)
        println("Start - ${System.currentTimeMillis()}")
        val job = launch(SupervisorJob()) {
            client.prepareGet("http://localhost:8080/infiniteData").execute { response ->
                response.bodyAsChannel().toInputStream().also { inputStream ->
                    while (inputStream.read(dataOut).also { println("Read - $it") } > 0);
                }
            }
        }
        delay(5_000)
        println("Requested cancel")
        job.cancelAndJoin()
        println("Cancelled")
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    // Takes 12s
    fun put1(): Unit = runBlocking {
        val data = List(100) { Random.nextBytes(10_000_000).toHexString() }
        println("WTF")
        val client = HttpClient()
        val start = System.currentTimeMillis()
        repeat(500) { i ->
            client.preparePost("http://localhost:8080/sink") {
//                setBody(TextContent(data[i%100], ContentType.Application.Json))
                setBody(WriterContent(contentType = ContentType.Application.Json, body = {
                    write(data[i%100])
                }))
            }.execute {
                it.bodyAsText()
            }
        }
        println("Done in ${System.currentTimeMillis() - start}")
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    // Takes 12s
    fun put2(): Unit = runBlocking {
        val data = List(100) { Random.nextBytes(10_000_000).toHexString() }
        val httpClient = NettyWebClient()
        val uri = URI("http://localhost:8080/sink")
        val start = System.currentTimeMillis()
        repeat(500) {
            httpClient.uri(uri).method(HttpMethod.POST).body(data[it%100]).retrieve().toTextFlow().toList().joinToString("")
        }
        println("Done in ${System.currentTimeMillis() - start}")
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    // Takes 10s
    fun putBytes1(): Unit = runBlocking {
        val data = List(100) { Random.nextBytes(20_000_000) }
        println("WTF")
        val client = HttpClient()
        val start = System.currentTimeMillis()
        repeat(500) { i ->
            client.preparePost("http://localhost:8080/sink") {
//                setBody(TextContent(data[i%100], ContentType.Application.Json))
                setBody(ChannelWriterContent(contentType = ContentType.Application.Json, body = {
                    this.writeFully(ByteBuffer.wrap(data[i%100]))
                }))
            }.execute {
                it.bodyAsText()
            }
        }
        println("Done in ${System.currentTimeMillis() - start}")
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    // Takes 10s
    fun putBytes2(): Unit = runBlocking {
        val data = List(100) { Random.nextBytes(20_000_000) }
        val httpClient = NettyWebClient()
        val uri = URI("http://localhost:8080/sink")
        val start = System.currentTimeMillis()
        repeat(500) {
            httpClient.uri(uri).method(HttpMethod.POST).body(flowOf(ByteBuffer.wrap(data[it%100]))).retrieve().toTextFlow().toList().joinToString("")
        }
        println("Done in ${System.currentTimeMillis() - start}")
    }

//    @Test
//    fun readBytes1Streaming(): Unit = runBlocking {
//        val client = HttpClient()
//        val start = System.currentTimeMillis()
//        repeat(500) {
//            flow<ByteBuffer> {
//                client.prepareGet("http://localhost:8080/giantJson").execute {
//                    val bodyBuffer = it.bodyAsChannel().readBuffer()
//                    while (!bodyBuffer.exhausted()) {
//                        UnsafeBufferOperations.readFromHead(bodyBuffer) { array, start, endExclusive ->
//                            emit(ByteBuffer.wrap(array, start, endExclusive - start))
//                            endExclusive - start
//                        }
//                    }
//                }
//            }.collect {}
//        }
//        println("Done in ${System.currentTimeMillis() - start}")
//    }

    @OptIn(InternalAPI::class, InternalIoApi::class)
    @Test
    fun readBytes1Copying(): Unit = runBlocking {
        val client = HttpClient()
        val start = System.currentTimeMillis()
        val expected = client.get("http://localhost:8080/giantJson").bodyAsText()
        repeat(5000) { i ->
            if (i % 100 == 0) println(i)
            flow<ByteBuffer> {
                client.prepareGet("http://localhost:8080/giantJson").execute {
                    val channel = it.bodyAsChannel()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (
                            read > 0) {
                            emit(ByteBuffer.wrap(buffer.copyOfRange(0, read)))
                        }
                    }
                }
            }.toByteArray().also {
                check(expected == it.decodeToString()) { "Invalid body received" }
            }
        }
        println("Done in ${System.currentTimeMillis() - start}")
    }


    @Test
    fun readBytes2(): Unit = runBlocking {
        val httpClient = NettyWebClient()
        val uri = URI("http://localhost:8080/giantJson")
        val start = System.currentTimeMillis()
        val expected = HttpClient().get("http://localhost:8080/giantJson").bodyAsText()
        repeat(5000) {
            httpClient.uri(uri).method(HttpMethod.GET).retrieve().toFlow().toByteArray().also {
                check(expected == it.decodeToString()) { "Invalid body received" }
            }
        }
        println("Done in ${System.currentTimeMillis() - start}")
    }
}

suspend fun Flow<ByteBuffer>.toByteArray(): ByteArray =
    ByteArrayOutputStream().use { os ->
        collect {
            it.writeTo(os)
        }
        os.toByteArray()
    }


private fun ByteBuffer.writeTo(os: OutputStream): Unit =
    if (hasArray() && hasRemaining()) {
        os.write(array(), position() + arrayOffset(), remaining())
    } else {
        os.write(ByteArray(remaining()).also { get(it) })
    }