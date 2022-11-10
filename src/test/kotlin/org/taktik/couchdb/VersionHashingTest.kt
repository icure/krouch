package org.taktik.couchdb

import org.junit.jupiter.api.Test
import org.taktik.couchdb.dao.UserDAO
import org.taktik.couchdb.support.StdDesignDocumentFactory
import org.junit.jupiter.api.Assertions.assertEquals

class VersionHashingTest {

    @Test
    fun calculatingTheHashMultipleTimesReturnsTheSameResult() {
        val documentFactory = StdDesignDocumentFactory()

        val design1 = documentFactory.generateFrom("_design/User", UserDAO())
        val design2 = documentFactory.generateFrom("_design/User", UserDAO())

        assertEquals(design2.id, design1.id)
    }

}