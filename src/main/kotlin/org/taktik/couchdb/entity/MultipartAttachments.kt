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

package org.taktik.couchdb.entity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.ByteBuffer

/**
 * The content of an attachment to write in the same request as its document (multipart/related PUT).
 *
 * @param contentType the MIME type CouchDB stores for the attachment.
 * @param length the exact number of bytes [data] emits. CouchDB reads exactly this many bytes for the attachment, so
 * the write fails if [data] emits more or fewer bytes.
 * @param data the attachment bytes. It is collected once, while the request body is sent.
 */
class AttachmentUpload(
    val contentType: String,
    val length: Long,
    val data: Flow<ByteBuffer>,
) {
    init {
        require(length >= 0) { "Attachment length cannot be negative" }
        // The content type is written as a part header: control characters would let it inject headers or parts.
        require(contentType.isNotBlank() && contentType.none { it.isISOControl() }) { "Invalid attachment content type" }
    }

    companion object {
        /**
         * An upload of [bytes]. Its data can be collected several times (e.g. when a request is retried): each
         * collection emits a new buffer over [bytes].
         */
        fun of(contentType: String, bytes: ByteArray) =
            AttachmentUpload(contentType, bytes.size.toLong(), flow { emit(ByteBuffer.wrap(bytes)) })
    }
}

/**
 * The decoded content of an attachment read together with its document.
 *
 * @param contentType the MIME type CouchDB stores for the attachment, if it sent one.
 * @param data the attachment bytes, already decompressed if CouchDB stored them compressed.
 */
class AttachmentContent(
    val contentType: String?,
    val data: ByteArray,
)

/**
 * A document read together with the content of its attachments.
 *
 * @param document the document. Its `_attachments` entries describe the attachments but carry no data.
 * @param attachments the content of each attachment, by attachment name.
 */
class DocumentWithAttachments<T>(
    val document: T,
    val attachments: Map<String, AttachmentContent>,
)
