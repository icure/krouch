package org.taktik.couchdb.support.generators.lists

import org.taktik.couchdb.annotation.ListFunction
import org.taktik.couchdb.annotation.Lists
import org.taktik.couchdb.support.generators.loadFromFile
import org.taktik.couchdb.util.Assert
import org.taktik.couchdb.util.eachAnnotation

class DefaultListGenerator<T : Any> : ListGenerator<T> {

	override fun generateListFunctions(metaDataSource: T): Map<String, String> {
		val metaDataClass = metaDataSource.javaClass
		val lists = mutableMapOf<String, String>()

		eachAnnotation(
			clazz = metaDataClass,
			annotationClass = ListFunction::class.java,
			filter = { true }
		) { input ->
			lists[input.name] = resolveListFunction(input, metaDataClass)
		}

		eachAnnotation(
			clazz = metaDataClass,
			annotationClass = Lists::class.java,
			filter = { true }
		) { input ->
			for (lf in input.value) {
				lists[lf.name] = resolveListFunction(lf, metaDataClass)
			}
		}

		return lists
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


}