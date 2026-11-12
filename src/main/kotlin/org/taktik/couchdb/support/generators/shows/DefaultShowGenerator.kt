package org.taktik.couchdb.support.generators.shows

import org.taktik.couchdb.annotation.ShowFunction
import org.taktik.couchdb.annotation.Shows
import org.taktik.couchdb.support.generators.loadFromFile
import org.taktik.couchdb.util.Assert
import org.taktik.couchdb.util.eachAnnotation

class DefaultShowGenerator<T : Any> : ShowGenerator<T> {

	override fun generateShowFunctions(metaDataSource: T): Map<String, String> {
		val metaDataClass = metaDataSource.javaClass
		val shows = mutableMapOf<String, String>()

		eachAnnotation(
			clazz = metaDataClass,
			annotationClass = ShowFunction::class.java,
			filter = { true }
		) { input ->
			shows[input.name] = resolveShowFunction(input, metaDataClass)
		}

		eachAnnotation(
			clazz = metaDataClass,
			annotationClass = Shows::class.java,
			filter = { true }
		) { input ->
			for (sf in input.value) {
				shows[sf.name] = resolveShowFunction(sf, metaDataClass)
			}
		}

		return shows
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

}