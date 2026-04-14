package org.taktik.couchdb.support.generators

import org.taktik.couchdb.util.Exceptions
import java.io.FileNotFoundException

fun loadFromFile(metaDataClass: Class<*>, file: String): String {
	return try {
		(metaDataClass.getResourceAsStream(file)
			?: throw FileNotFoundException("Could not load file with path: $file")).readAllBytes().toString(Charsets.UTF_8)
	} catch (e: Exception) {
		throw Exceptions.propagate(e)
	}
}