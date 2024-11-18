package org.taktik.couchdb

class DeserializationTypeException(objectId: String) : IllegalArgumentException("Object with ID $objectId is not of expected type")