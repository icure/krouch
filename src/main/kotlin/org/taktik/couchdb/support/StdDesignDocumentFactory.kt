package org.taktik.couchdb.support

class StdDesignDocumentFactory : AbstractDesignDocumentFactory<Any>(SimpleViewGenerator()) {

	override fun getMetadataClass(metaDataSource: Any): Class<*> = metaDataSource.javaClass
}