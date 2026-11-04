package org.taktik.couchdb.util

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

	@Test
	fun `encode then decode round-trips binary content containing the delimiter`() {
		val tricky = "x\r\n--B\r\nnot a part".toByteArray() + ByteArray(256) { it.toByte() }
		val body = MultipartRelated.encode(
			"B",
			"{}".toByteArray(),
			listOf(
				"one" to AttachmentUpload.of("application/octet-stream", tricky),
				"two" to AttachmentUpload.of("text/plain", ByteArray(0)),
			),
		)
		val parts = MultipartRelated.decode("B", body.bytes())
		assertEquals(3, parts.size)
		assertEquals("application/json", parts[0].contentType)
		assertArrayEquals("{}".toByteArray(), parts[0].body)
		assertEquals("one", parts[1].filename)
		assertArrayEquals(tricky, parts[1].body)
		assertEquals("two", parts[2].filename)
		assertEquals(0, parts[2].body.size)
	}

	@Test
	fun `decode inflates gzip parts and reads parts without Content-Length`() {
		val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("compressible".toByteArray()) } }.toByteArray()
		val body = "--B\r\nContent-Type: application/json\r\n\r\n{}\r\n--B".toByteArray() +
			"\r\nContent-Disposition: attachment; filename=\"a;b\"\r\nContent-Type: text/plain\r\nContent-Length: ${gzipped.size}\r\nContent-Encoding: gzip\r\n\r\n".toByteArray() +
			gzipped +
			"\r\n--B\r\nContent-Type: text/plain\r\n\r\nno length\r\n--B--".toByteArray()
		val parts = MultipartRelated.decode("B", body)
		assertEquals(3, parts.size)
		assertEquals("a;b", parts[1].filename)
		assertArrayEquals(gzipped, parts[1].body)
		assertArrayEquals("compressible".toByteArray(), parts[1].decodedBody(maxSize = 12))
		assertThrows<IllegalStateException> { parts[1].decodedBody(maxSize = 11) }
		assertNull(parts[2].filename)
		assertArrayEquals("no length".toByteArray(), parts[2].body)
	}

	@Test
	fun `an attachment content type with line breaks is rejected`() {
		assertThrows<IllegalArgumentException> {
			AttachmentUpload.of("text/plain\r\nContent-Type: text/html", ByteArray(0))
		}
	}

	@Test
	fun `boundaryOf reads quoted and unquoted boundaries and ignores non multipart types`() {
		assertEquals("abc", MultipartRelated.boundaryOf("multipart/related; boundary=\"abc\""))
		assertEquals("abc", MultipartRelated.boundaryOf("Multipart/Related;boundary=abc"))
		assertNull(MultipartRelated.boundaryOf("application/json"))
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
