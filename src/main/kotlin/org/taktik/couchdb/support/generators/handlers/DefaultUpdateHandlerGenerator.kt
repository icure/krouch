package org.taktik.couchdb.support.generators.handlers

import org.taktik.couchdb.annotation.UpdateHandler
import org.taktik.couchdb.annotation.UpdateHandlers
import org.taktik.couchdb.support.generators.loadFromFile
import org.taktik.couchdb.util.Assert
import org.taktik.couchdb.util.Predicate
import org.taktik.couchdb.util.eachAnnotation

class DefaultUpdateHandlerGenerator<T : Any> : UpdateHandlerGenerator<T> {

	override fun generateUpdateHandlerFunctions(metaDataSource: T): Map<String, String> {
		val metaDataClass = metaDataSource.javaClass
		val updateHandlers = mutableMapOf<String, String>()

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


}