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

import org.apache.commons.codec.digest.DigestUtils
import org.taktik.couchdb.entity.DesignDocument
import org.taktik.couchdb.support.generators.ExternalViewGenerator
import org.taktik.couchdb.support.generators.SimpleViewGenerator
import org.taktik.couchdb.support.generators.ViewGenerator
import org.taktik.couchdb.support.generators.filters.DefaultFilterGenerator
import org.taktik.couchdb.support.generators.filters.FilterGenerator
import org.taktik.couchdb.support.generators.handlers.DefaultUpdateHandlerGenerator
import org.taktik.couchdb.support.generators.handlers.UpdateHandlerGenerator
import org.taktik.couchdb.support.generators.lists.DefaultListGenerator
import org.taktik.couchdb.support.generators.lists.ListGenerator
import org.taktik.couchdb.support.generators.shows.DefaultShowGenerator
import org.taktik.couchdb.support.generators.shows.ShowGenerator
import org.taktik.couchdb.support.views.ExternalViewRepository

/**
 *
 * @author Antoine Duchâteau, based on of Ektorp by henrik lundgren
 */
class DesignDocumentFactory<T : Any> private constructor(
	private val viewGenerator: ViewGenerator<T>,
	private val listGenerator: ListGenerator<T>,
	private val showGenerator: ShowGenerator<T>,
	private val filterGenerator: FilterGenerator<T>,
	private val updateHandlerGenerator: UpdateHandlerGenerator<T>,
) {

	companion object {
		fun getExternalDesignDocumentFactory(): DesignDocumentFactory<ExternalViewRepository> = DesignDocumentFactory(
			viewGenerator = ExternalViewGenerator(),
			listGenerator = DefaultListGenerator(),
			showGenerator = DefaultShowGenerator(),
			filterGenerator = DefaultFilterGenerator(),
			updateHandlerGenerator = DefaultUpdateHandlerGenerator(),
		)

		fun getStdDesignDocumentFactory(): DesignDocumentFactory<Any> = DesignDocumentFactory(
			viewGenerator = SimpleViewGenerator(),
			listGenerator = DefaultListGenerator(),
			showGenerator = DefaultShowGenerator(),
			filterGenerator = DefaultFilterGenerator(),
			updateHandlerGenerator = DefaultUpdateHandlerGenerator(),
		)
	}

	fun generateFrom(baseId: String, metaDataSource: T, useVersioning: Boolean = true): Set<DesignDocument> {
		val (prefix, suffix) = baseId.split("/").let { it.subList(0, it.size - 1).joinToString("/") to it.last() }
		val views = viewGenerator.generateViews(metaDataSource, suffix)

		return views.toList().groupBy { (k,_) -> k.split("/")[0] }.map { (name, views) ->
			DesignDocument(
				id = if (useVersioning) "${prefix}/${name}_${createViewVersionHash(views.map { it.second })}" else "${prefix}/${name}",
				views = views.associate { (k, v) -> k.split("/")[1] to v },
				lists = listGenerator.generateListFunctions(metaDataSource),
				shows = showGenerator.generateShowFunctions(metaDataSource),
				filters = filterGenerator.generateFilterFunctions(metaDataSource),
				updateHandlers = updateHandlerGenerator.generateUpdateHandlerFunctions(metaDataSource)
			)
		}.toSet()
	}

	private fun createViewVersionHash(views: Collection<org.taktik.couchdb.entity.View>) = DigestUtils.sha256Hex(views.sortedBy { it.map }.joinToString { it.toString() }).substring(0, 4)

}
