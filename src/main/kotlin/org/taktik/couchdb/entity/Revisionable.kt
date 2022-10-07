package org.taktik.couchdb.entity

import org.taktik.couchdb.id.Identifiable

interface Revisionable<T> : Identifiable<T> {
    val rev: String?

    fun withIdRev(id: T? = null, rev: String): Versionable<T>
}
