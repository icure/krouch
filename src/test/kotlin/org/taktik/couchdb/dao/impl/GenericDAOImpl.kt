/*
 *    Copyright 2020 Taktik SA
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

package org.taktik.couchdb.dao.impl

import org.taktik.couchdb.Client
import org.taktik.couchdb.CouchDbDocument
import org.taktik.couchdb.dao.GenericDAO
import org.taktik.couchdb.entity.DesignDocument
import org.taktik.couchdb.get
import org.taktik.couchdb.support.DesignDocumentFactory

abstract class GenericDAOImpl<T : CouchDbDocument>(
    entityClass: Class<T>,
    protected val client: Client
) : GenericDAO<T> {

    val designDocumentId = "_design/${entityClass.simpleName}"

    override suspend fun createOrUpdateDesignDocuments(updateIfExists: Boolean, useVersioning: Boolean) {
            val designDocuments = DesignDocumentFactory.getStdDesignDocumentFactory().generateFrom(designDocumentId, this, useVersioning)
        designDocuments.forEach { designDocument ->
            val existingDesignDocument: DesignDocument? = client.get(designDocument.id)
            val (merged, changed) = existingDesignDocument?.mergeWith(designDocument, true) ?: (designDocument to true)
            if (changed && (existingDesignDocument == null || updateIfExists)) {
                existingDesignDocument?.let { client.update(if (it.id == designDocument.id) merged.copy(rev = it.rev) else merged, DesignDocument::class.java) }
                    ?: client.create(merged, DesignDocument::class.java)
            }
        }
    }
}
