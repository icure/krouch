package org.taktik.couchdb

import io.icure.asyncjacksonhttpclient.netty.NettyWebClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import org.taktik.couchdb.exception.CouchDbConflictException
import java.net.URI
import java.nio.ByteBuffer
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
	private suspend fun attachmentBytes(id: String, name: String) =
		client.getWithAttachments(id, Code::class.java)!!.attachments.getValue(name).data

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
		val read = client.getWithAttachments(first.id, Code::class.java)!!
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
	fun `getWithAttachments decodes compressed and binary attachments and returns stubs`() = runBlocking {
		val updated = client.updateWithAttachments(
			client.create(newCode()),
			Code::class.java,
			mapOf(
				"text" to AttachmentUpload.of("text/plain", text),
				"bin" to AttachmentUpload.of("application/octet-stream", binary),
			),
		)
		val read = client.getWithAttachments(updated.id, Code::class.java)!!
		assertEquals(updated.rev, read.document.rev)
		assertArrayEquals(text, read.attachments.getValue("text").data)
		assertEquals("text/plain", read.attachments.getValue("text").contentType)
		assertArrayEquals(binary, read.attachments.getValue("bin").data)
		assertEquals("application/octet-stream", read.attachments.getValue("bin").contentType)
		assertTrue(read.document.attachments!!.values.all { it.isStub })

		// The document read this way can be saved back as it is.
		val saved = client.update(read.document.copy(label = mapOf("en" to "saved back")), Code::class.java)
		assertArrayEquals(text, attachmentBytes(saved.id, "text"))
	}

	@Test
	fun `getWithAttachments reads an older revision`() = runBlocking {
		val first = client.updateWithAttachments(
			client.create(newCode()),
			Code::class.java,
			mapOf("v" to AttachmentUpload.of("text/plain", "v1".toByteArray())),
		)
		client.updateWithAttachments(first, Code::class.java, mapOf("v" to AttachmentUpload.of("text/plain", "v2".toByteArray())))
		val old = client.getWithAttachments(first.id, Code::class.java, rev = first.rev)!!
		assertArrayEquals("v1".toByteArray(), old.attachments.getValue("v").data)
		assertArrayEquals("v2".toByteArray(), attachmentBytes(first.id, "v"))
	}

	@Test
	fun `getWithAttachments on a document without attachments returns no attachment`() = runBlocking {
		val created = client.create(newCode())
		val read = client.getWithAttachments(created.id, Code::class.java)
		assertNotNull(read)
		assertEquals(created.rev, read!!.document.rev)
		assertTrue(read.attachments.isEmpty())
	}

	@Test
	fun `getWithAttachments on a missing document returns null`() = runBlocking {
		assertNull(client.getWithAttachments(UUID.randomUUID().toString(), Code::class.java))
	}
}
