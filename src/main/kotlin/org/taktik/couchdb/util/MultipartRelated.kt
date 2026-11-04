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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.taktik.couchdb.entity.AttachmentUpload
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/**
 * Encoding and decoding of the `multipart/related` bodies CouchDB uses to carry a document and its attachments in one
 * request. The layout is the one CouchDB itself writes (`couch_httpd_multipart:encode_multipart_stream`):
 *
 * ```
 * --B\r\nContent-Type: application/json\r\n\r\n{json}\r\n--B
 * \r\nContent-Disposition: attachment; filename="a"\r\nContent-Type: t\r\nContent-Length: n\r\n\r\n{bytes}\r\n--B
 * ...
 * --
 * ```
 *
 * CouchDB matches attachment parts to the `"follows": true` entries of `_attachments` by position, not by name, so the
 * parts must be written in the order of those entries.
 */
object MultipartRelated {
    private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
    private val DOUBLE_CRLF = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
    private val DASH_DASH = "--".toByteArray(Charsets.US_ASCII)

    fun contentType(boundary: String) = "multipart/related; boundary=\"$boundary\""

    /**
     * An encoded body: its exact length, for the `Content-Length` header, and its bytes.
     */
    class EncodedBody(val contentLength: Long, val data: Flow<ByteBuffer>)

    /**
     * Encodes [json] followed by [attachments], in the iteration order of [attachments].
     *
     * The returned flow fails with an [IllegalStateException] if an attachment's data emits more or fewer bytes than its
     * declared [AttachmentUpload.length], before any byte past the declared length is emitted.
     */
    fun encode(boundary: String, json: ByteArray, attachments: List<Pair<String, AttachmentUpload>>): EncodedBody {
        require(boundary.isNotEmpty() && boundary.length <= 70) { "Invalid boundary" }
        val delimiter = "--$boundary".toByteArray(Charsets.US_ASCII)
        val head = "--$boundary\r\nContent-Type: application/json\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val partHeaders = attachments.map { (name, upload) ->
            require(!name.contains('"') && !name.contains('\r') && !name.contains('\n')) {
                "Attachment name cannot contain quotes or line breaks: $name"
            }
            (
                "\r\nContent-Disposition: attachment; filename=\"$name\"" +
                    "\r\nContent-Type: ${upload.contentType}" +
                    "\r\nContent-Length: ${upload.length}\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
        }
        val contentLength = head.size + json.size + CRLF.size + delimiter.size +
            attachments.indices.sumOf { i ->
                partHeaders[i].size + attachments[i].second.length + CRLF.size + delimiter.size
            } + DASH_DASH.size

        val data = flow {
            emit(ByteBuffer.wrap(head))
            emit(ByteBuffer.wrap(json))
            emit(ByteBuffer.wrap(CRLF))
            emit(ByteBuffer.wrap(delimiter))
            attachments.forEachIndexed { i, (name, upload) ->
                emit(ByteBuffer.wrap(partHeaders[i]))
                var sent = 0L
                upload.data.collect { buffer ->
                    sent += buffer.remaining()
                    check(sent <= upload.length) {
                        "Attachment $name emitted more than its declared length of ${upload.length} bytes"
                    }
                    emit(buffer)
                }
                check(sent == upload.length) {
                    "Attachment $name emitted $sent bytes but declared a length of ${upload.length} bytes"
                }
                emit(ByteBuffer.wrap(CRLF))
                emit(ByteBuffer.wrap(delimiter))
            }
            emit(ByteBuffer.wrap(DASH_DASH))
        }
        return EncodedBody(contentLength, data)
    }

    /**
     * One decoded part.
     *
     * @param headers the part headers, with lower-case names.
     * @param body the part body as it was sent, still encoded if the part has a `Content-Encoding`: see [decodedBody].
     */
    class Part(val headers: Map<String, String>, val body: ByteArray) {
        /**
         * The part body with its `Content-Encoding` (`gzip` or none) undone.
         *
         * @param maxSize the largest decoded size accepted: decoding stops with an [IllegalStateException] past it.
         */
        fun decodedBody(maxSize: Long): ByteArray = when (headers["content-encoding"]?.lowercase()) {
            null, "identity" -> body
            "gzip" -> GZIPInputStream(body.inputStream()).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= maxSize) { "Decoded part is larger than $maxSize bytes" }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            else -> throw IllegalStateException("Unsupported part Content-Encoding: ${headers["content-encoding"]}")
        }

        /** The `filename` parameter of the `Content-Disposition` header, if any. */
        val filename: String?
            get() = headers["content-disposition"]?.let { disposition ->
                // CouchDB writes the name between quotes without escaping it, so it can itself contain ';'.
                val quoted = disposition.indexOf("filename=\"", ignoreCase = true)
                if (quoted >= 0 && disposition.endsWith("\"")) {
                    disposition.substring(quoted + "filename=\"".length, disposition.length - 1)
                } else parameter(disposition, "filename")
            }
        val contentType: String?
            get() = headers["content-type"]
    }

    /**
     * Returns the `boundary` parameter of a `multipart/...` content type, or null if [contentType] is not multipart.
     */
    fun boundaryOf(contentType: String): String? =
        if (contentType.trimStart().startsWith("multipart/", ignoreCase = true)) parameter(contentType, "boundary") else null

    /**
     * Decodes a complete multipart body. For each part, a `Content-Length` header is trusted when the delimiter follows
     * the announced length; otherwise the body runs up to the next delimiter.
     */
    fun decode(boundary: String, body: ByteArray): List<Part> {
        val delimiter = "--$boundary".toByteArray(Charsets.US_ASCII)
        val crlfDelimiter = CRLF + delimiter
        var pos = indexOf(body, delimiter, 0)
        check(pos >= 0) { "Multipart body has no delimiter for boundary $boundary" }
        pos += delimiter.size
        val parts = mutableListOf<Part>()
        while (true) {
            if (startsWith(body, DASH_DASH, pos)) return parts
            // Skip transport padding, then the line break that ends the delimiter line.
            while (pos < body.size && (body[pos] == ' '.code.toByte() || body[pos] == '\t'.code.toByte())) pos++
            check(startsWith(body, CRLF, pos)) { "Malformed multipart delimiter line at offset $pos" }
            pos += CRLF.size

            val headers: Map<String, String>
            if (startsWith(body, CRLF, pos)) {
                headers = emptyMap()
                pos += CRLF.size
            } else {
                val headersEnd = indexOf(body, DOUBLE_CRLF, pos)
                check(headersEnd >= 0) { "Unterminated multipart headers at offset $pos" }
                headers = String(body, pos, headersEnd - pos, Charsets.UTF_8)
                    .split("\r\n")
                    .filter { it.isNotBlank() }
                    .associate { line ->
                        val colon = line.indexOf(':')
                        check(colon > 0) { "Malformed multipart header: $line" }
                        line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
                    }
                pos = headersEnd + DOUBLE_CRLF.size
            }

            val announcedLength = headers["content-length"]?.toLongOrNull()
            val bodyEnd = announcedLength
                ?.takeIf { it <= body.size - pos && startsWith(body, crlfDelimiter, pos + it.toInt()) }
                ?.let { pos + it.toInt() }
                ?: indexOf(body, crlfDelimiter, pos).also {
                    check(it >= 0) { "Unterminated multipart part at offset $pos" }
                }
            parts.add(Part(headers, body.copyOfRange(pos, bodyEnd)))
            pos = bodyEnd + crlfDelimiter.size
        }
    }

    private fun parameter(headerValue: String, name: String): String? =
        headerValue.split(';').drop(1).firstNotNullOfOrNull { param ->
            val eq = param.indexOf('=')
            if (eq > 0 && param.substring(0, eq).trim().equals(name, ignoreCase = true)) {
                param.substring(eq + 1).trim().removeSurrounding("\"")
            } else null
        }

    private fun startsWith(haystack: ByteArray, needle: ByteArray, from: Int): Boolean {
        if (from < 0 || from + needle.size > haystack.size) return false
        for (i in needle.indices) if (haystack[from + i] != needle[i]) return false
        return true
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        var i = from
        val last = haystack.size - needle.size
        while (i <= last) {
            if (startsWith(haystack, needle, i)) return i
            i++
        }
        return -1
    }
}
