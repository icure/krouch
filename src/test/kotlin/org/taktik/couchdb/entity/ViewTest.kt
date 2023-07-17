package org.taktik.couchdb.entity

import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

class ViewTest {

    @Test
    fun testEquals() {
        assertEquals(View(map = "map = function(doc) { emit(1) }"), View(map = "function(doc) { emit(1) }"))
        assertNotEquals(View(map = "map = function(doc) { emit(2) }"), View(map = "function(doc) { emit(1) }"))
    }
}
