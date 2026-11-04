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
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

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
    private val SPACE: Byte = ' '.code.toByte()
    private val TAB: Byte = '\t'.code.toByte()
    private const val MAX_HEADERS_SIZE = 64 * 1024
    const val OUTPUT_CHUNK_SIZE = 64 * 1024

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

    /** An event of [Parser]. */
    sealed interface PartEvent {
        /** A part starts. [headers] have lower-case names. */
        class Start(val headers: Map<String, String>) : PartEvent {
            /** The `filename` parameter of the `Content-Disposition` header, if any. */
            val filename: String?
                get() = headers["content-disposition"]?.let { disposition ->
                    // CouchDB writes the name between quotes without escaping it, so it can itself contain ';'.
                    val quoted = disposition.indexOf("filename=\"", ignoreCase = true)
                    if (quoted >= 0 && disposition.endsWith("\"")) {
                        disposition.substring(quoted + "filename=\"".length, disposition.length - 1)
                    } else parameter(disposition, "filename")
                }
            val contentType: String? get() = headers["content-type"]
            val contentEncoding: String? get() = headers["content-encoding"]?.lowercase()
        }

        /** A chunk of the current part's body, as sent (still encoded if the part has a `Content-Encoding`). */
        class Data(val data: ByteArray) : PartEvent

        /** The current part ends. */
        data object End : PartEvent
    }

    /**
     * Incremental parser of a multipart body: [feed] it the body chunk by chunk, then call [finish].
     *
     * Only headers and a delimiter's worth of look-ahead are buffered: a part body is passed to [sink] as it arrives.
     * A part with a `Content-Length` header is read for exactly that length, and must be followed by the delimiter;
     * a part without one (CouchDB's JSON part) runs up to the next delimiter.
     */
    class Parser(boundary: String, private val sink: suspend (PartEvent) -> Unit) {
        private enum class State { PREAMBLE, AFTER_DELIMITER, HEADERS, COUNTED_BODY, CLOSING_DELIMITER, SCANNED_BODY, DONE }

        private val delimiter = "--$boundary".toByteArray(Charsets.US_ASCII)
        private val bodyDelimiter = CRLF + delimiter
        private var state = State.PREAMBLE
        private var pending = ByteArray(0)
        private var remaining = 0L

        suspend fun feed(chunk: ByteBuffer) {
            val input = ByteArray(chunk.remaining()).also { chunk.get(it) }
            val data = if (pending.isEmpty()) input else pending + input
            pending = ByteArray(0)
            var pos = 0
            while (true) {
                when (state) {
                    State.PREAMBLE -> {
                        val idx = indexOf(data, delimiter, pos)
                        if (idx < 0) {
                            pos = maxOf(pos, data.size - delimiter.size + 1)
                            break
                        }
                        pos = idx + delimiter.size
                        state = State.AFTER_DELIMITER
                    }
                    State.AFTER_DELIMITER -> {
                        // Transport padding, then "--" (closing delimiter) or the line break before the headers.
                        while (pos < data.size && (data[pos] == SPACE || data[pos] == TAB)) pos++
                        if (data.size - pos < 2) break
                        when {
                            startsWith(data, DASH_DASH, pos) -> {
                                pos = data.size // The epilogue, if any, is ignored.
                                state = State.DONE
                            }
                            startsWith(data, CRLF, pos) -> {
                                pos += CRLF.size
                                state = State.HEADERS
                            }
                            else -> throw IllegalStateException("Malformed multipart delimiter line")
                        }
                    }
                    State.HEADERS -> {
                        val headers = if (startsWith(data, CRLF, pos)) {
                            pos += CRLF.size
                            emptyMap()
                        } else {
                            val end = indexOf(data, DOUBLE_CRLF, pos)
                            if (end < 0) {
                                check(data.size - pos <= MAX_HEADERS_SIZE) { "Multipart part headers are too large" }
                                break
                            }
                            parseHeaders(String(data, pos, end - pos, Charsets.UTF_8)).also { pos = end + DOUBLE_CRLF.size }
                        }
                        sink(PartEvent.Start(headers))
                        val contentLength = headers["content-length"]?.let {
                            checkNotNull(it.toLongOrNull()?.takeIf { l -> l >= 0 }) { "Invalid part Content-Length: $it" }
                        }
                        if (contentLength != null) {
                            remaining = contentLength
                            state = State.COUNTED_BODY
                        } else {
                            state = State.SCANNED_BODY
                        }
                    }
                    State.COUNTED_BODY -> {
                        val n = minOf(remaining, (data.size - pos).toLong()).toInt()
                        if (n > 0) sink(PartEvent.Data(data.copyOfRange(pos, pos + n)))
                        pos += n
                        remaining -= n
                        if (remaining > 0) break
                        state = State.CLOSING_DELIMITER
                    }
                    State.CLOSING_DELIMITER -> {
                        if (data.size - pos < bodyDelimiter.size) break
                        check(startsWith(data, bodyDelimiter, pos)) { "Multipart part is longer than its Content-Length" }
                        pos += bodyDelimiter.size
                        sink(PartEvent.End)
                        state = State.AFTER_DELIMITER
                    }
                    State.SCANNED_BODY -> {
                        val idx = indexOf(data, bodyDelimiter, pos)
                        if (idx >= 0) {
                            if (idx > pos) sink(PartEvent.Data(data.copyOfRange(pos, idx)))
                            pos = idx + bodyDelimiter.size
                            sink(PartEvent.End)
                            state = State.AFTER_DELIMITER
                        } else {
                            // Keep what could be the start of a delimiter split across chunks.
                            val safeEnd = maxOf(pos, data.size - bodyDelimiter.size + 1)
                            if (safeEnd > pos) sink(PartEvent.Data(data.copyOfRange(pos, safeEnd)))
                            pos = safeEnd
                            break
                        }
                    }
                    State.DONE -> {
                        pos = data.size
                        break
                    }
                }
            }
            if (pos < data.size) pending = data.copyOfRange(pos, data.size)
        }

        /** Checks the body was complete. */
        fun finish() {
            check(state == State.DONE) { "Multipart body ended before its closing delimiter" }
        }

        private fun parseHeaders(block: String) = block.split("\r\n")
            .filter { it.isNotBlank() }
            .associate { line ->
                val colon = line.indexOf(':')
                check(colon > 0) { "Malformed multipart header: $line" }
                line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }
    }

    /**
     * Incremental gzip decoder: [feed] it the compressed bytes chunk by chunk, then call [finish]. Decompressed bytes are
     * passed to [sink] as they are produced, at most [OUTPUT_CHUNK_SIZE] bytes at a time.
     *
     * @param maxSize the largest decompressed size accepted: decoding fails past it, which bounds what a corrupt or
     * malicious stream can expand to.
     */
    class GzipDecoder(private val maxSize: Long, private val sink: suspend (ByteBuffer) -> Unit) {
        private enum class State { HEADER, BODY, TRAILER, DONE }

        private var state = State.HEADER
        private var pending = ByteArray(0)
        private val inflater = Inflater(true)
        private val crc = CRC32()
        private val output = ByteArray(OUTPUT_CHUNK_SIZE)
        private var total = 0L

        suspend fun feed(input: ByteArray) {
            var data = if (pending.isEmpty()) input else pending + input
            pending = ByteArray(0)
            while (data.isNotEmpty()) {
                when (state) {
                    State.HEADER -> {
                        val headerSize = gzipHeaderSize(data)
                        if (headerSize < 0) {
                            check(data.size <= MAX_HEADERS_SIZE) { "gzip header is too large" }
                            pending = data
                            return
                        }
                        data = data.copyOfRange(headerSize, data.size)
                        state = State.BODY
                    }
                    State.BODY -> {
                        inflater.setInput(data)
                        while (true) {
                            val n = try {
                                inflater.inflate(output)
                            } catch (e: DataFormatException) {
                                throw IllegalStateException("Invalid gzip data", e)
                            }
                            if (n > 0) {
                                total += n
                                check(total <= maxSize) { "Decompressed data is larger than $maxSize bytes" }
                                crc.update(output, 0, n)
                                sink(ByteBuffer.wrap(output.copyOf(n)))
                            }
                            if (inflater.finished()) {
                                data = data.copyOfRange(data.size - inflater.remaining, data.size)
                                state = State.TRAILER
                                break
                            }
                            check(!inflater.needsDictionary()) { "gzip data needs a preset dictionary" }
                            if (n == 0 && inflater.needsInput()) {
                                data = ByteArray(0)
                                break
                            }
                        }
                    }
                    State.TRAILER -> {
                        if (data.size < GZIP_TRAILER_SIZE) {
                            pending = data
                            return
                        }
                        val trailer = ByteBuffer.wrap(data, 0, GZIP_TRAILER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                        check(trailer.int.toLong() and 0xffffffffL == crc.value) { "gzip CRC mismatch" }
                        check(trailer.int.toLong() and 0xffffffffL == total and 0xffffffffL) { "gzip size mismatch" }
                        check(data.size == GZIP_TRAILER_SIZE) { "Unexpected data after the gzip stream" }
                        data = ByteArray(0)
                        state = State.DONE
                    }
                    State.DONE -> throw IllegalStateException("Unexpected data after the gzip stream")
                }
            }
        }

        /** Checks the stream was complete and releases the inflater. */
        fun finish() {
            inflater.end()
            check(state == State.DONE) { "gzip stream is truncated" }
        }

        /** The size of the gzip header at the start of [data], or -1 if [data] does not hold all of it yet. */
        private fun gzipHeaderSize(data: ByteArray): Int {
            if (data.size < 10) return -1
            check(data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) { "Not gzip data" }
            check(data[2] == 8.toByte()) { "Unsupported gzip compression method ${data[2]}" }
            val flags = data[3].toInt()
            var pos = 10
            if (flags and FEXTRA != 0) {
                if (data.size < pos + 2) return -1
                pos += 2 + ((data[pos].toInt() and 0xff) or ((data[pos + 1].toInt() and 0xff) shl 8))
            }
            for (flag in listOf(FNAME, FCOMMENT)) {
                if (flags and flag != 0) {
                    while (pos < data.size && data[pos] != 0.toByte()) pos++
                    if (pos >= data.size) return -1
                    pos++
                }
            }
            if (flags and FHCRC != 0) pos += 2
            return if (pos <= data.size) pos else -1
        }

        private companion object {
            const val FHCRC = 2
            const val FEXTRA = 4
            const val FNAME = 8
            const val FCOMMENT = 16
            const val GZIP_TRAILER_SIZE = 8
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
