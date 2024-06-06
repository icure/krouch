package org.taktik.couchdb.support

import org.taktik.couchdb.support.views.ExternalViewRepository

class ExternalDesignDocumentFactory : AbstractDesignDocumentFactory<ExternalViewRepository>(
	ExternalViewGenerator()
) {
	override fun getMetadataClass(metaDataSource: ExternalViewRepository): Class<*> = metaDataSource.javaClass
}