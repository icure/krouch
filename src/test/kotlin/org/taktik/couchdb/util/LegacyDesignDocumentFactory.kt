package org.taktik.couchdb.util

import org.apache.commons.codec.digest.DigestUtils
import org.taktik.couchdb.annotation.Filter
import org.taktik.couchdb.annotation.Filters
import org.taktik.couchdb.annotation.ListFunction
import org.taktik.couchdb.annotation.Lists
import org.taktik.couchdb.annotation.ShowFunction
import org.taktik.couchdb.annotation.Shows
import org.taktik.couchdb.annotation.UpdateHandler
import org.taktik.couchdb.annotation.UpdateHandlers
import org.taktik.couchdb.entity.DesignDocument
import org.taktik.couchdb.support.generators.views.ViewGenerator
import java.io.FileNotFoundException

/**
 *
 * @author Antoine Duchâteau, based on of Ektorp by henrik lundgren
 */
class LegacyDesignDocumentFactory<T : Any>(
	private val viewGenerator: ViewGenerator<T>
) {

	companion object {
		fun getStdDesignDocumentFactory() =
			LegacyDesignDocumentFactory(SimpleViewGenerator())
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.ektorp.support.DesignDocumentFactory#generateFrom(java.lang.Object)
	 */
	fun generateFrom(baseId: String, metaDataSource: T, useVersioning: Boolean = true): Set<DesignDocument> {
		val (prefix, suffix) = baseId.split("/").let { it.subList(0, it.size - 1).joinToString("/") to it.last() }
		val metaDataClass = metaDataSource.javaClass
		val views = viewGenerator.generateViews(metaDataSource, suffix)

		return views.toList().groupBy { (k,_) -> k.split("/")[0] }.map { (name, views) ->
			DesignDocument(
				id = if (useVersioning) "${prefix}/${name}_${createViewVersionHash(views.map { it.second })}" else "${prefix}/${name}",
				views = views.associate { (k, v) -> k.split("/")[1] to v },
				lists = createListFunctions(metaDataClass),
				shows = createShowFunctions(metaDataClass),
				filters = createFilterFunctions(metaDataClass),
				updateHandlers = createUpdateHandlerFunctions(metaDataClass)
			)
		}.toSet()
	}

	private fun createViewVersionHash(views: Collection<org.taktik.couchdb.entity.View>) = DigestUtils.sha256Hex(views.map { it.sha }.sorted().joinToString()).substring(0, 8)

	private fun createFilterFunctions(metaDataClass: Class<*>): Map<String, String> {
		val shows: MutableMap<String, String> = HashMap()

		eachAnnotation(metaDataClass, Filter::class.java, object : Predicate<Filter> {
			override fun apply(input: Filter): Boolean {
				shows[input.name] = resolveFilterFunction(input, metaDataClass)
				return true
			}
		})


		eachAnnotation(metaDataClass, Filters::class.java, object : Predicate<Filters> {
			override fun apply(input: Filters): Boolean {
				for (sf in input.value) {
					shows[sf.name] = resolveFilterFunction(sf, metaDataClass)
				}
				return true
			}
		})

		return shows
	}

	private fun createUpdateHandlerFunctions(metaDataClass: Class<*>): Map<String, String> {
		val updateHandlers: MutableMap<String, String> = HashMap()

		eachAnnotation(metaDataClass, UpdateHandler::class.java, object : Predicate<UpdateHandler> {
			override fun apply(input: UpdateHandler): Boolean {
				updateHandlers[input.name] = resolveUpdateHandlerFunction(input, metaDataClass)
				return true
			}
		})

		eachAnnotation(metaDataClass, UpdateHandlers::class.java, object : Predicate<UpdateHandlers> {
			override fun apply(input: UpdateHandlers): Boolean {
				for (sf in input.value) {
					updateHandlers[sf.name] = resolveUpdateHandlerFunction(sf, metaDataClass)
				}
				return true
			}
		})

		return updateHandlers
	}

	private fun createShowFunctions(metaDataClass: Class<*>): Map<String, String> {
		val shows: MutableMap<String, String> = HashMap()

		eachAnnotation(metaDataClass, ShowFunction::class.java, object : Predicate<ShowFunction> {
			override fun apply(input: ShowFunction): Boolean {
				shows[input.name] = resolveShowFunction(input, metaDataClass)
				return true
			}
		})

		eachAnnotation(metaDataClass, Shows::class.java, object : Predicate<Shows> {
			override fun apply(input: Shows): Boolean {
				for (sf in input.value) {
					shows[sf.name] = resolveShowFunction(sf, metaDataClass)
				}
				return true
			}
		})

		return shows
	}

	private fun createListFunctions(metaDataClass: Class<*>): Map<String, String> {
		val lists: MutableMap<String, String> = HashMap()

		eachAnnotation(metaDataClass, ListFunction::class.java, object : Predicate<ListFunction> {
			override fun apply(input: ListFunction): Boolean {
				lists[input.name] = resolveListFunction(input, metaDataClass)
				return true
			}
		})

		eachAnnotation(metaDataClass, Lists::class.java, object : Predicate<Lists> {
			override fun apply(input: Lists): Boolean {
				for (lf in input.value) {
					lists[lf.name] = resolveListFunction(lf, metaDataClass)
				}
				return true
			}
		})

		return lists
	}

	private fun resolveFilterFunction(
		input: Filter,
		metaDataClass: Class<*>,
	): String {
		if (input.file.isNotEmpty()) {
			return loadFromFile(metaDataClass, input.file)
		}
		Assert.hasText(input.function, "Filter must either have file or function value set")
		return input.function
	}

	private fun resolveUpdateHandlerFunction(
		input: UpdateHandler,
		metaDataClass: Class<*>,
	): String {
		if (input.file.isNotEmpty()) {
			return loadFromFile(metaDataClass, input.file)
		}
		Assert.hasText(input.function, "UpdateHandler must either have file or function value set")
		return input.function
	}

	private fun resolveListFunction(
		input: ListFunction,
		metaDataClass: Class<*>,
	): String {
		if (input.file.isNotEmpty()) {
			return loadFromFile(metaDataClass, input.file)
		}
		Assert.hasText(input.function, "ListFunction must either have file or function value set")
		return input.function
	}

	private fun resolveShowFunction(
		input: ShowFunction,
		metaDataClass: Class<*>,
	): String {
		if (input.file.isNotEmpty()) {
			return loadFromFile(metaDataClass, input.file)
		}
		Assert.hasText(input.function, "ShowFunction must either have file or function value set")
		return input.function
	}

	private fun loadFromFile(metaDataClass: Class<*>, file: String): String {
		return try {
			(metaDataClass.getResourceAsStream(file)
				?: throw FileNotFoundException("Could not load file with path: $file")).readAllBytes().toString(Charsets.UTF_8)
		} catch (e: Exception) {
			throw Exceptions.propagate(e)
		}
	}
}
