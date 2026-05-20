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
package org.taktik.couchdb.support

import org.taktik.couchdb.entity.DesignDocument
import org.taktik.couchdb.support.generators.ddoc.DefaultDesignDocGenerator
import org.taktik.couchdb.support.generators.ddoc.DesignDocGenerator
import org.taktik.couchdb.support.generators.views.ExternalViewGenerator
import org.taktik.couchdb.support.generators.views.SimpleViewGenerator
import org.taktik.couchdb.support.generators.views.ViewGenerator
import org.taktik.couchdb.support.generators.filters.DefaultFilterGenerator
import org.taktik.couchdb.support.generators.filters.FilterGenerator
import org.taktik.couchdb.support.generators.filters.NoOpFilterGenerator
import org.taktik.couchdb.support.generators.handlers.DefaultUpdateHandlerGenerator
import org.taktik.couchdb.support.generators.handlers.NoOpUpdateHandlerGenerator
import org.taktik.couchdb.support.generators.handlers.UpdateHandlerGenerator
import org.taktik.couchdb.support.generators.lib.LibGenerator
import org.taktik.couchdb.support.generators.lib.NoOpLibGenerator
import org.taktik.couchdb.support.generators.lists.DefaultListGenerator
import org.taktik.couchdb.support.generators.lists.ListGenerator
import org.taktik.couchdb.support.generators.lists.NoOpListGenerator
import org.taktik.couchdb.support.generators.shows.DefaultShowGenerator
import org.taktik.couchdb.support.generators.shows.NoOpShowGenerator
import org.taktik.couchdb.support.generators.shows.ShowGenerator
import org.taktik.couchdb.support.repositories.ExternalViewRepository

class DesignDocumentFactory<T : Any> private constructor(
	private val viewGenerator: ViewGenerator<T>,
	private val libGenerator: LibGenerator<T>,
	private val listGenerator: ListGenerator<T>,
	private val showGenerator: ShowGenerator<T>,
	private val filterGenerator: FilterGenerator<T>,
	private val updateHandlerGenerator: UpdateHandlerGenerator<T>,
	private val designDocGenerator: DesignDocGenerator<T>,
) {

	companion object {
		fun getExternalDesignDocumentFactory(): DesignDocumentFactory<ExternalViewRepository> = DesignDocumentFactory(
			viewGenerator = ExternalViewGenerator,
			libGenerator = NoOpLibGenerator(),
			listGenerator = DefaultListGenerator(),
			showGenerator = DefaultShowGenerator(),
			filterGenerator = DefaultFilterGenerator(),
			updateHandlerGenerator = DefaultUpdateHandlerGenerator(),
			designDocGenerator = DefaultDesignDocGenerator(),
		)

		fun getStdDesignDocumentFactory(): DesignDocumentFactory<Any> = DesignDocumentFactory(
			viewGenerator = SimpleViewGenerator,
			libGenerator = NoOpLibGenerator(),
			listGenerator = DefaultListGenerator(),
			showGenerator = DefaultShowGenerator(),
			filterGenerator = DefaultFilterGenerator(),
			updateHandlerGenerator = DefaultUpdateHandlerGenerator(),
			designDocGenerator = DefaultDesignDocGenerator(),
		)

		fun <T: Any> getDesignDocumentFactoryWith(
			viewGenerator: ViewGenerator<T>,
			libGenerator: LibGenerator<T>,
			designDocGenerator: DesignDocGenerator<T>
		): DesignDocumentFactory<T> = DesignDocumentFactory(
			viewGenerator = viewGenerator,
			libGenerator = libGenerator,
			listGenerator = NoOpListGenerator(),
			showGenerator = NoOpShowGenerator(),
			filterGenerator = NoOpFilterGenerator(),
			updateHandlerGenerator = NoOpUpdateHandlerGenerator(),
			designDocGenerator = designDocGenerator
		)
	}

	@Deprecated("baseId is implicitly parsed into entity name and partition, and this can be not clear.")
	fun generateFrom(baseId: String, metaDataSource: T, useVersioning: Boolean = true): Set<DesignDocument> {
		val nameAndPartition = baseId.split("/").last()
		return if (nameAndPartition.contains("-")) {
			val (name, partition) = nameAndPartition.split("-", limit = 2)
			generateFrom(
				designDocEntityName = name,
				partition = partition,
				metaDataSource = metaDataSource,
				useVersioning = useVersioning
			)
		} else {
			generateFrom(
				designDocEntityName = nameAndPartition,
				partition = null,
				metaDataSource = metaDataSource,
				useVersioning = useVersioning
			)
		}
	}

	fun generateFrom(designDocEntityName: String, partition: String?, metaDataSource: T, useVersioning: Boolean = true): Set<DesignDocument> {
		val views = viewGenerator.generateViews(
			repository = metaDataSource,
			ddocEntityName = designDocEntityName,
		)

		return designDocGenerator.splitViewsAndGenerateDesignDocs(
			entityName = designDocEntityName,
			views = views,
			metadataSource = metaDataSource,
			useVersioning = useVersioning
		) { id, partition, generatedViews ->
			DesignDocument(
				id = id,
				views = generatedViews,
				lib = libGenerator.generateLibResources(partition, metaDataSource),
				lists = listGenerator.generateListFunctions(metaDataSource),
				shows = showGenerator.generateShowFunctions(metaDataSource),
				filters = filterGenerator.generateFilterFunctions(metaDataSource),
				updateHandlers = updateHandlerGenerator.generateUpdateHandlerFunctions(metaDataSource)
			)
		}
	}

}
