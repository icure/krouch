package org.taktik.couchdb

import org.junit.jupiter.api.Test
import org.taktik.couchdb.dao.UserDAO
import org.taktik.couchdb.support.StdDesignDocumentFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.RepeatedTest

class VersionHashingTest {

    @RepeatedTest(value = 1000)
    fun calculatingTheHashMultipleTimesReturnsTheSameResult() {
        val documentFactory = StdDesignDocumentFactory()

        val design1 = documentFactory.generateFrom("_design/User", UserDAO()).first()
        val design2 = documentFactory.generateFrom("_design/User", UserDAO()).first()

        assertEquals(design2.id, design1.id)
    }

}
