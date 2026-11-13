package org.taktik.couchdb.support.generators.filters

import org.taktik.couchdb.annotation.Filter
import org.taktik.couchdb.annotation.Filters
import org.taktik.couchdb.support.generators.loadFromFile
import org.taktik.couchdb.util.Assert
import org.taktik.couchdb.util.eachAnnotation

class DefaultFilterGenerator<T : Any> : FilterGenerator<T> {

	override fun generateFilterFunctions(metaDataSource: T): Map<String, String> {
		val metaDataClass = metaDataSource.javaClass
		val shows = mutableMapOf<String, String>()

		eachAnnotation(
			clazz = metaDataClass,
			annotationClass = Filter::class.java,
			filter = { true }
		) { input ->
			shows[input.name] = resolveFilterFunction(input, metaDataClass)
		}


		eachAnnotation(
			clazz = metaDataClass,
			annotationClass = Filters::class.java,
			filter = { true }
		) { input ->
			for (sf in input.value) {
				shows[sf.name] = resolveFilterFunction(sf, metaDataClass)
			}
		}

		return shows
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
}