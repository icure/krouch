/*
 *    Copyright 2026 Taktik SA
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

package org.taktik.couchdb.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.taktik.couchdb.entity.DocumentStreamEvent
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Decodes, chunk by chunk, the answer of CouchDB to `GET /db/id?attachments=true` with
 * `Accept: multipart/related` into [DocumentStreamEvent]s passed to [sink].
 *
 * CouchDB answers with a `multipart/related` body when the document has attachments (the JSON document, then one part
 * per attachment, possibly gzip-encoded) and with plain JSON otherwise. The multipart body starts with its delimiter,
 * so the boundary is read from the body and the response headers are not needed.
 *
 * Only the JSON document is held in memory; attachment content is passed on as it arrives.
 */
internal class DocumentStreamDecoder<T>(
    private val objectMapper: ObjectMapper,
    private val clazz: Class<T>,
    private val sink: suspend (DocumentStreamEvent<T>) -> Unit,
) {
    private class Declared(val length: Long, val contentType: String?)

    private class CurrentAttachment(val name: String, val length: Long, val gzip: MultipartRelated.GzipDecoder?) {
        var emitted = 0L
    }

    private var head = ByteArray(0)
    private var parser: MultipartRelated.Parser? = null
    private var isJson = false
    private val documentBytes = ByteArrayOutputStream()
    private var partIndex = -1
    private var documentEmitted = false
    private var followingNames = emptyList<String>()
    private var declared = emptyMap<String, Declared>()
    private var current: CurrentAttachment? = null

    suspend fun feed(chunk: ByteBuffer) {
        parser?.let { return it.feed(chunk) }
        if (isJson) return appendDocument(ByteArray(chunk.remaining()).also { chunk.get(it) })

        // Find out from the first bytes whether the body is JSON or multipart (and then its boundary).
        head += ByteArray(chunk.remaining()).also { chunk.get(it) }
        val first = head.indexOfFirst { !it.toInt().toChar().isWhitespace() }
        if (first < 0) return
        if (head[first] == '{'.code.toByte()) {
            isJson = true
            appendDocument(head)
        } else {
            val lineEnd = (first until head.size - 1).firstOrNull { head[it] == '\r'.code.toByte() && head[it + 1] == '\n'.code.toByte() }
            if (lineEnd == null) {
                check(head.size <= MAX_HEAD_SIZE) { "Unexpected response from CouchDB: neither JSON nor multipart" }
                return
            }
            val firstLine = String(head, first, lineEnd - first, Charsets.US_ASCII).trimEnd()
            check(firstLine.startsWith("--") && firstLine.length > 2) { "Unexpected response from CouchDB: neither JSON nor multipart" }
            parser = MultipartRelated.Parser(firstLine.substring(2), ::onPartEvent).also { it.feed(ByteBuffer.wrap(head)) }
        }
        head = ByteArray(0)
    }

    suspend fun finish() {
        val parser = parser
        when {
            parser != null -> {
                parser.finish()
                check(documentEmitted) { "Multipart response from CouchDB has no document part" }
            }
            isJson -> emitDocument(documentBytes.toByteArray(), inlineAttachments = true)
            else -> throw IllegalStateException("Empty or truncated response from CouchDB")
        }
    }

    private fun appendDocument(bytes: ByteArray) {
        check(documentBytes.size() + bytes.size <= MAX_DOCUMENT_SIZE) { "Document is larger than $MAX_DOCUMENT_SIZE bytes" }
        documentBytes.write(bytes)
    }

    private suspend fun onPartEvent(event: MultipartRelated.PartEvent) {
        when (event) {
            is MultipartRelated.PartEvent.Start -> {
                partIndex++
                if (partIndex == 0) return
                check(documentEmitted) { "Attachment part before the document part" }
                val name = event.filename ?: followingNames.getOrNull(partIndex - 1)
                    ?: throw IllegalStateException("Cannot match attachment part $partIndex to an attachment of the document")
                val length = declared[name]?.length
                    ?: throw IllegalStateException("Attachment $name is not declared by the document")
                val gzip = when (event.contentEncoding) {
                    null, "identity" -> null
                    // The declared length is the decompressed size: it bounds the inflation.
                    "gzip" -> MultipartRelated.GzipDecoder(maxSize = length) { emitData(it) }
                    else -> throw IllegalStateException("Unsupported Content-Encoding ${event.contentEncoding} for attachment $name")
                }
                current = CurrentAttachment(name, length, gzip)
                sink(DocumentStreamEvent.AttachmentStart(name, event.contentType ?: declared[name]?.contentType, length))
            }
            is MultipartRelated.PartEvent.Data -> {
                if (partIndex == 0) return appendDocument(event.data)
                val gzip = checkNotNull(current).gzip
                if (gzip != null) gzip.feed(event.data) else emitData(ByteBuffer.wrap(event.data))
            }
            MultipartRelated.PartEvent.End -> {
                if (partIndex == 0) return emitDocument(documentBytes.toByteArray(), inlineAttachments = false)
                val attachment = checkNotNull(current)
                attachment.gzip?.finish()
                check(attachment.emitted == attachment.length) {
                    "Attachment ${attachment.name} has ${attachment.emitted} bytes but the document declares ${attachment.length}"
                }
                current = null
                sink(DocumentStreamEvent.AttachmentEnd(attachment.name))
            }
        }
    }

    private suspend fun emitData(data: ByteBuffer) {
        val attachment = checkNotNull(current)
        attachment.emitted += data.remaining()
        check(attachment.emitted <= attachment.length) {
            "Attachment ${attachment.name} is larger than the ${attachment.length} bytes the document declares"
        }
        sink(DocumentStreamEvent.AttachmentData(attachment.name, data))
    }

    /**
     * Emits the document with stub `_attachments`, so that it can be saved back as it is. With [inlineAttachments]
     * (plain JSON answer), the attachments whose content is inlined as base64 `data` are emitted after it.
     */
    private suspend fun emitDocument(bytes: ByteArray, inlineAttachments: Boolean) {
        val document = objectMapper.readTree(bytes) as ObjectNode
        val attachmentsNode = document.get("_attachments") as? ObjectNode
        val following = mutableListOf<String>()
        val declaredByName = mutableMapOf<String, Declared>()
        val inline = mutableListOf<Pair<String, ByteArray>>()
        attachmentsNode?.fields()?.forEach { (name, node) ->
            node as ObjectNode
            val contentType = node.get("content_type")?.asText()
            node.get("length")?.asLong()?.let { declaredByName[name] = Declared(it, contentType) }
            if (node.get("follows")?.asBoolean() == true) following.add(name)
            if (inlineAttachments && node.hasNonNull("data")) inline.add(name to Base64.getDecoder().decode(node.get("data").asText()))
            node.remove(listOf("follows", "data", "encoding", "encoded_length"))
            node.put("stub", true)
        }
        followingNames = following
        declared = declaredByName
        documentEmitted = true
        sink(DocumentStreamEvent.Document(objectMapper.treeToValue(document, clazz)))
        inline.forEach { (name, data) ->
            sink(DocumentStreamEvent.AttachmentStart(name, declaredByName[name]?.contentType, data.size.toLong()))
            sink(DocumentStreamEvent.AttachmentData(name, ByteBuffer.wrap(data)))
            sink(DocumentStreamEvent.AttachmentEnd(name))
        }
    }

    private companion object {
        const val MAX_HEAD_SIZE = 1024
        const val MAX_DOCUMENT_SIZE = 64 * 1024 * 1024
    }
}
