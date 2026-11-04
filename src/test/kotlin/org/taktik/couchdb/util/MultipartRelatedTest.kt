package org.taktik.couchdb.util

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.taktik.couchdb.entity.AttachmentUpload
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream

class MultipartRelatedTest {
	private fun MultipartRelated.EncodedBody.bytes(): ByteArray = runBlocking {
		ByteArrayOutputStream().also { out ->
			data.toList().forEach { out.write(ByteArray(it.remaining()).also { bytes -> it.get(bytes) }) }
		}.toByteArray()
	}

	private class ParsedPart(val headers: Map<String, String>, val filename: String?) {
		val body = ByteArrayOutputStream()
		var ended = false
	}

	/** Parses [body] fed [chunkSize] bytes at a time. */
	private fun parse(boundary: String, body: ByteArray, chunkSize: Int): List<ParsedPart> = runBlocking {
		val parts = mutableListOf<ParsedPart>()
		val parser = MultipartRelated.Parser(boundary) { event ->
			when (event) {
				is MultipartRelated.PartEvent.Start -> parts.add(ParsedPart(event.headers, event.filename))
				is MultipartRelated.PartEvent.Data -> parts.last().body.write(event.data)
				MultipartRelated.PartEvent.End -> parts.last().ended = true
			}
		}
		body.asSequence().chunked(chunkSize).forEach { parser.feed(ByteBuffer.wrap(it.toByteArray())) }
		parser.finish()
		parts
	}

	/** Inflates [data] fed [chunkSize] bytes at a time. */
	private fun gunzip(data: ByteArray, chunkSize: Int, maxSize: Long = Long.MAX_VALUE): ByteArray = runBlocking {
		val out = ByteArrayOutputStream()
		val decoder = MultipartRelated.GzipDecoder(maxSize) { out.write(ByteArray(it.remaining()).also { bytes -> it.get(bytes) }) }
		data.asSequence().chunked(chunkSize).forEach { decoder.feed(it.toByteArray()) }
		decoder.finish()
		out.toByteArray()
	}

	private fun gzip(data: ByteArray) =
		ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

	@Test
	fun `encode writes the layout CouchDB writes, with an exact content length`() {
		val body = MultipartRelated.encode(
			"B",
			"""{"_id":"x"}""".toByteArray(),
			listOf("a.txt" to AttachmentUpload.of("text/plain", "hello".toByteArray())),
		)
		val bytes = body.bytes()
		assertEquals(
			"--B\r\nContent-Type: application/json\r\n\r\n{\"_id\":\"x\"}\r\n--B" +
				"\r\nContent-Disposition: attachment; filename=\"a.txt\"\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello\r\n--B" +
				"--",
			String(bytes, Charsets.UTF_8),
		)
		assertEquals(bytes.size.toLong(), body.contentLength)
	}

	@ParameterizedTest
	@ValueSource(ints = [1, 2, 7, 4096])
	fun `the parser reads what encode writes, whatever the chunking`(chunkSize: Int) {
		// Content that contains the delimiter: only the Content-Length tells where the part ends.
		val tricky = "x\r\n--B\r\nnot a part".toByteArray() + ByteArray(256) { it.toByte() }
		val body = MultipartRelated.encode(
			"B",
			"""{"a":"\r\n-"}""".toByteArray(),
			listOf(
				"one" to AttachmentUpload.of("application/octet-stream", tricky),
				"two;x" to AttachmentUpload.of("text/plain", ByteArray(0)),
			),
		).bytes()
		val parts = parse("B", body, chunkSize)
		assertEquals(3, parts.size)
		assertTrue(parts.all { it.ended })
		assertEquals("application/json", parts[0].headers["content-type"])
		assertArrayEquals("""{"a":"\r\n-"}""".toByteArray(), parts[0].body.toByteArray())
		assertEquals("one", parts[1].filename)
		assertArrayEquals(tricky, parts[1].body.toByteArray())
		assertEquals("two;x", parts[2].filename)
		assertEquals(0, parts[2].body.size())
	}

	@ParameterizedTest
	@ValueSource(ints = [1, 3, 4096])
	fun `the parser finds the end of parts without Content-Length across chunks`(chunkSize: Int) {
		val body = "--Bnd\r\nContent-Type: text/plain\r\n\r\nalmost \r\n--Bn but not quite\r\n--Bnd\r\n\r\nno headers\r\n--Bnd--\r\nepilogue".toByteArray()
		val parts = parse("Bnd", body, chunkSize)
		assertEquals(2, parts.size)
		assertEquals("almost \r\n--Bn but not quite", parts[0].body.toString(Charsets.UTF_8))
		assertEquals(emptyMap<String, String>(), parts[1].headers)
		assertEquals("no headers", parts[1].body.toString(Charsets.UTF_8))
	}

	@Test
	fun `the parser rejects truncated bodies and parts longer than their Content-Length`() {
		assertThrows<IllegalStateException> { parse("B", "--B\r\n\r\n{}\r\n--B".toByteArray(), 4096) }
		assertThrows<IllegalStateException> {
			parse("B", "--B\r\nContent-Length: 2\r\n\r\nabc\r\n--B--".toByteArray(), 4096)
		}
	}

	@ParameterizedTest
	@ValueSource(ints = [1, 3, 4096])
	fun `the gzip decoder inflates a stream whatever the chunking`(chunkSize: Int) {
		val original = ("compressible text ".repeat(5000)).toByteArray()
		assertArrayEquals(original, gunzip(gzip(original), chunkSize))
	}

	@Test
	fun `the gzip decoder skips optional header fields`() {
		val original = "named".toByteArray()
		val plain = gzip(original)
		// Same stream with the FNAME flag and a file name after the 10-byte header.
		val named = plain.copyOfRange(0, 10).also { it[3] = 8 } + "file.txt\u0000".toByteArray() + plain.copyOfRange(10, plain.size)
		assertArrayEquals(original, gunzip(named, 2))
	}

	@Test
	fun `the gzip decoder enforces the maximum size and detects corruption and truncation`() {
		val compressed = gzip(ByteArray(100_000))
		assertThrows<IllegalStateException> { gunzip(compressed, 4096, maxSize = 99_999) }
		assertThrows<IllegalStateException> { gunzip(compressed.copyOf(compressed.size - 4), 4096) }
		val corrupted = compressed.copyOf().also { it[it.size - 6] = (it[it.size - 6] + 1).toByte() }
		assertThrows<IllegalStateException> { gunzip(corrupted, 4096) }
	}

	@Test
	fun `an attachment content type with line breaks is rejected`() {
		assertThrows<IllegalArgumentException> {
			AttachmentUpload.of("text/plain\r\nContent-Type: text/html", ByteArray(0))
		}
	}

	@Test
	fun `encoding fails when an attachment emits fewer or more bytes than declared`() {
		val tooShort = MultipartRelated.encode(
			"B", "{}".toByteArray(),
			listOf("a" to AttachmentUpload("text/plain", 10, flowOf(ByteBuffer.wrap("short".toByteArray())))),
		)
		assertThrows<IllegalStateException> { tooShort.bytes() }
		val tooLong = MultipartRelated.encode(
			"B", "{}".toByteArray(),
			listOf("a" to AttachmentUpload("text/plain", 2, flowOf(ByteBuffer.wrap("long".toByteArray())))),
		)
		assertThrows<IllegalStateException> { tooLong.bytes() }
	}
}
