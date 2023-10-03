package org.taktik.couchdb.util

import io.icure.asyncjacksonhttpclient.net.addSinglePathComponent
import java.net.URI

fun URI.appendDocumentOrDesignDocId(id: String) =
    if (id.startsWith("_design/")) {
        id.split("/").fold(this) { u, it ->
            u.addSinglePathComponent(it)
        }
    } else addSinglePathComponent(id)