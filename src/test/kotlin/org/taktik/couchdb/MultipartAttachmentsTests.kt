package org.taktik.couchdb

import io.icure.asyncjacksonhttpclient.netty.NettyWebClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbPassword
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUrl
import org.taktik.couchdb.DockerSetupListener.Companion.couchDbUsername
import org.taktik.couchdb.entity.Attachment
import org.taktik.couchdb.entity.AttachmentUpload
import org.taktik.couchdb.entity.DocumentStreamEvent
import org.taktik.couchdb.exception.CouchDbConflictException
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

@ExperimentalCoroutinesApi
class MultipartAttachmentsTests {
	private val client = ClientImpl(
		NettyWebClient(),
		URI(couchDbUrl),
		System.getProperty("krouch.test.couchdb.database.name", "krouch-test"),
		couchDbUsername,
		couchDbPassword,
		strictMode = true
	)

	@BeforeEach
	fun setupDatabase() = runBlocking {
		if (!client.exists()) {
			client.create(8, 2)
		}
	}

	private fun newCode() = Code.from("multipart", UUID.randomUUID().toString(), "1")
	private val String.generation get() = substringBefore('-').toInt()

	private class ReadAttachment(val contentType: String?, val announcedLength: Long, val data: ByteArray, val chunks: Int)
	private class Read(val document: Code, val attachments: Map<String, ReadAttachment>)

	/** Collects [Client.getWithAttachments], checking the order of the events. */
	private suspend fun read(id: String, rev: String? = null): Read? {
		val events = client.getWithAttachments(id, Code::class.java, rev).toList()
		if (events.isEmpty()) return null
		val document = (events.first() as DocumentStreamEvent.Document<Code>).document
		val attachments = mutableMapOf<String, ReadAttachment>()
		var i = 1
		while (i < events.size) {
			val start = events[i++] as DocumentStreamEvent.AttachmentStart
			val out = ByteArrayOutputStream()
			var chunks = 0
			while (events[i] is DocumentStreamEvent.AttachmentData) {
				val data = events[i++] as DocumentStreamEvent.AttachmentData
				assertEquals(start.name, data.name)
				out.write(ByteArray(data.data.remaining()).also { data.data.get(it) })
				chunks++
			}
			assertEquals(start.name, (events[i++] as DocumentStreamEvent.AttachmentEnd).name)
			attachments[start.name] = ReadAttachment(start.contentType, start.length, out.toByteArray(), chunks)
		}
		return Read(document, attachments)
	}

	private suspend fun attachmentBytes(id: String, name: String) = read(id)!!.attachments.getValue(name).data

	private val binary = ByteArray(5000) { (it * 31).toByte() }
	private val text = ("compressible text ".repeat(500)).toByteArray()

	@Test
	fun `updateWithAttachments stores the document and its attachment in a single revision`() = runBlocking {
		val created = client.create(newCode())
		val updated = client.updateWithAttachments(
			created.copy(label = mapOf("en" to "with attachment")),
			Code::class.java,
			mapOf("bin" to AttachmentUpload.of("application/octet-stream", binary)),
		)
		assertEquals(created.rev!!.generation + 1, updated.rev!!.generation)

		val read = client.get(created.id, Code::class.java)!!
		assertEquals(updated.rev, read.rev)
		assertEquals("with attachment", read.label["en"])
		assertEquals(binary.size.toLong(), read.attachments?.get("bin")?.length)
		assertArrayEquals(binary, attachmentBytes(created.id, "bin"))
	}

	@Test
	fun `stubs keep existing attachments and omitted ones are removed`() = runBlocking {
		val first = client.updateWithAttachments(
			client.create(newCode()),
			Code::class.java,
			mapOf(
				"keep" to AttachmentUpload.of("application/octet-stream", binary),
				"drop" to AttachmentUpload.of("text/plain", text),
			),
		)
		client.updateWithAttachments(
			first.copy(attachments = mapOf("keep" to Attachment(isStub = true))),
			Code::class.java,
			mapOf("added" to AttachmentUpload.of("text/plain", "added".toByteArray())),
		)
		val read = read(first.id)!!
		assertEquals(setOf("keep", "added"), read.attachments.keys)
		assertEquals(setOf("keep", "added"), read.document.attachments?.keys)
		assertArrayEquals(binary, read.attachments.getValue("keep").data)
		assertArrayEquals("added".toByteArray(), read.attachments.getValue("added").data)
	}

	@Test
	fun `a stale revision is rejected and leaves the document unchanged`() = runBlocking {
		val created = client.create(newCode())
		val current = client.update(created.copy(label = mapOf("en" to "moved on")), Code::class.java)

		assertThrows<CouchDbConflictException> {
			runBlocking {
				client.updateWithAttachments(
					created,
					Code::class.java,
					mapOf("bin" to AttachmentUpload.of("application/octet-stream", binary)),
				)
			}
		}
		val read = client.get(created.id, Code::class.java)!!
		assertEquals(current.rev, read.rev)
		assertNull(read.attachments)
	}

	@Test
	fun `a declared length that does not match the data fails and leaves the document unchanged`() = runBlocking {
		val created = client.create(newCode())
		assertThrows<Exception> {
			runBlocking {
				client.updateWithAttachments(
					created,
					Code::class.java,
					mapOf("bin" to AttachmentUpload("application/octet-stream", 100, flowOf(ByteBuffer.wrap(ByteArray(10))))),
				)
			}
		}
		val read = client.get(created.id, Code::class.java)!!
		assertEquals(created.rev, read.rev)
		assertNull(read.attachments)
	}

	@Test
	fun `createWithAttachments creates the document with its attachments`() = runBlocking {
		val created = client.createWithAttachments(
			newCode(),
			Code::class.java,
			mapOf("bin" to AttachmentUpload.of("application/octet-stream", binary)),
		)
		assertEquals(1, created.rev!!.generation)
		assertArrayEquals(binary, attachmentBytes(created.id, "bin"))
	}

	@Test
	fun `getWithAttachments streams compressed and binary attachments after the document`() = runBlocking {
		val updated = client.updateWithAttachments(
			client.create(newCode()),
			Code::class.java,
			mapOf(
				"text" to AttachmentUpload.of("text/plain", text),
				"bin" to AttachmentUpload.of("application/octet-stream", binary),
			),
		)
		val read = read(updated.id)!!
		assertEquals(updated.rev, read.document.rev)
		assertArrayEquals(text, read.attachments.getValue("text").data)
		assertEquals(text.size.toLong(), read.attachments.getValue("text").announcedLength)
		assertEquals("text/plain", read.attachments.getValue("text").contentType)
		assertArrayEquals(binary, read.attachments.getValue("bin").data)
		assertEquals("application/octet-stream", read.attachments.getValue("bin").contentType)
		assertTrue(read.document.attachments!!.values.all { it.isStub })
		assertEquals(text.size.toLong(), read.document.attachments!!.getValue("text").length)

		// The document read this way can be saved back as it is.
		val saved = client.update(read.document.copy(label = mapOf("en" to "saved back")), Code::class.java)
		assertArrayEquals(text, attachmentBytes(saved.id, "text"))
	}

	/** [size] bytes of a deterministic pattern, generated chunk by chunk: never held in memory as a whole. */
	private fun generated(size: Long, compressible: Boolean): Flow<ByteBuffer> = flow {
		val chunk = 64 * 1024
		var sent = 0L
		while (sent < size) {
			val n = minOf(chunk.toLong(), size - sent).toInt()
			emit(ByteBuffer.wrap(ByteArray(n) { i -> if (compressible) ('a' + ((sent + i) % 26).toInt()).code.toByte() else ((sent + i) * 2654435761L shr 13).toByte() }))
			sent += n
		}
	}

	private suspend fun Flow<ByteBuffer>.sha256(): ByteArray {
		val digest = MessageDigest.getInstance("SHA-256")
		collect { digest.update(it) }
		return digest.digest()
	}

	@Test
	fun `large attachments are written and read as streams`() = runBlocking {
		val size = 20L * 1024 * 1024
		val updated = client.updateWithAttachments(
			client.create(newCode()),
			Code::class.java,
			mapOf(
				"big" to AttachmentUpload("application/octet-stream", size, generated(size, compressible = false)),
				"bigText" to AttachmentUpload("text/plain", size, generated(size, compressible = true)),
			),
		)
		val digests = mapOf("big" to MessageDigest.getInstance("SHA-256"), "bigText" to MessageDigest.getInstance("SHA-256"))
		val lengths = mutableMapOf<String, Long>()
		var maxChunk = 0
		var chunks = 0
		client.getWithAttachments(updated.id, Code::class.java).collect { event ->
			when (event) {
				is DocumentStreamEvent.AttachmentStart -> lengths[event.name] = event.length
				is DocumentStreamEvent.AttachmentData -> {
					maxChunk = maxOf(maxChunk, event.data.remaining())
					chunks++
					digests.getValue(event.name).update(event.data)
				}
				else -> {}
			}
		}
		assertEquals(mapOf("big" to size, "bigText" to size), lengths)
		assertArrayEquals(generated(size, compressible = false).sha256(), digests.getValue("big").digest())
		assertArrayEquals(generated(size, compressible = true).sha256(), digests.getValue("bigText").digest())
		// The content arrives in many small chunks, not as one buffer per attachment.
		assertTrue(chunks > 100) { "Only $chunks chunks" }
		assertTrue(maxChunk <= 1024 * 1024) { "A chunk of $maxChunk bytes" }
	}

	@Test
	fun `getWithAttachments reads an older revision`() = runBlocking {
		val first = client.updateWithAttachments(
			client.create(newCode()),
			Code::class.java,
			mapOf("v" to AttachmentUpload.of("text/plain", "v1".toByteArray())),
		)
		client.updateWithAttachments(first, Code::class.java, mapOf("v" to AttachmentUpload.of("text/plain", "v2".toByteArray())))
		assertArrayEquals("v1".toByteArray(), read(first.id, rev = first.rev)!!.attachments.getValue("v").data)
		assertArrayEquals("v2".toByteArray(), attachmentBytes(first.id, "v"))
	}

	@Test
	fun `getWithAttachments on a document without attachments emits only the document`() = runBlocking {
		val created = client.create(newCode())
		val read = read(created.id)!!
		assertEquals(created.rev, read.document.rev)
		assertTrue(read.attachments.isEmpty())
	}

	@Test
	fun `getWithAttachments on a missing document emits nothing`() = runBlocking {
		assertNull(read(UUID.randomUUID().toString()))
	}
}
