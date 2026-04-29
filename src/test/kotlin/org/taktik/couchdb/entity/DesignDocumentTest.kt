package org.taktik.couchdb.entity

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DesignDocumentTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `serialization moves lib into views under LIB_VIEW_KEY`() {
        val ddoc = DesignDocument(
            id = "_design/test",
            rev = "1-abc",
            views = mapOf("myView" to View(map = "function(doc) { emit(doc._id) }")),
            lib = mapOf("utils" to "exports.sum = function(a, b) { return a + b; }")
        )

        val tree = mapper.readTree(mapper.writeValueAsString(ddoc))

        assertNull(tree.get("lib"), "lib must not be a top-level field")
        val viewsNode = tree.get("views")
        assertNotNull(viewsNode)
        val libNode = viewsNode.get(DesignDocument.LIB_VIEW_KEY)
        assertNotNull(libNode, "views must contain a '${DesignDocument.LIB_VIEW_KEY}' entry")
        assertEquals("exports.sum = function(a, b) { return a + b; }", libNode.get("utils").asText())
        assertNotNull(viewsNode.get("myView"), "regular views must still be present")
    }

    @Test
    fun `deserialization extracts lib from views into lib field`() {
        val json = """
            {
                "_id": "_design/test",
                "_rev": "1-abc",
                "views": {
                    "myView": {"map": "function(doc) { emit(doc._id) }"},
                    "lib": {"utils": "exports.sum = function(a, b) { return a + b; }"}
                }
            }
        """.trimIndent()

        val ddoc = mapper.readValue(json, DesignDocument::class.java)

        assertEquals("_design/test", ddoc.id)
        assertEquals("1-abc", ddoc.rev)
        assertFalse(ddoc.views.containsKey(DesignDocument.LIB_VIEW_KEY), "lib must not appear in views")
        assertEquals(1, ddoc.views.size)
        assertNotNull(ddoc.views["myView"])
        assertEquals(mapOf("utils" to "exports.sum = function(a, b) { return a + b; }"), ddoc.lib)
    }

    @Test
    fun `serialization with empty lib does not add LIB_VIEW_KEY to views`() {
        val ddoc = DesignDocument(
            id = "_design/test",
            views = mapOf("myView" to View(map = "function(doc) { emit(doc._id) }"))
        )

        val tree = mapper.readTree(mapper.writeValueAsString(ddoc))

        assertNull(tree.get("lib"))
        assertNull(tree.get("views")?.get(DesignDocument.LIB_VIEW_KEY))
    }

    @Test
    fun `deserialization with no lib entry in views produces empty lib`() {
        val json = """
            {
                "_id": "_design/test",
                "views": {
                    "myView": {"map": "function(doc) { emit(doc._id) }"}
                }
            }
        """.trimIndent()

        val ddoc = mapper.readValue(json, DesignDocument::class.java)

        assertTrue(ddoc.lib.isEmpty())
        assertFalse(ddoc.views.containsKey(DesignDocument.LIB_VIEW_KEY))
    }

    @Test
    fun `roundtrip preserves all fields including lib`() {
        val original = DesignDocument(
            id = "_design/test",
            rev = "1-abc",
            language = "javascript",
            views = mapOf("myView" to View(map = "function(doc) { emit(doc._id) }", reduce = "_count")),
            lib = mapOf("utils" to "exports.sum = function(a, b) { return a + b; }"),
            lists = mapOf("myList" to "function(head, req) {}"),
            shows = mapOf("myShow" to "function(doc, req) {}"),
            filters = mapOf("myFilter" to "function(doc) { return true; }")
        )

        val deserialized = mapper.readValue(mapper.writeValueAsString(original), DesignDocument::class.java)

        assertEquals(original.id, deserialized.id)
        assertEquals(original.rev, deserialized.rev)
        assertEquals(original.language, deserialized.language)
        assertEquals(original.views, deserialized.views)
        assertEquals(original.lib, deserialized.lib)
        assertEquals(original.lists, deserialized.lists)
        assertEquals(original.shows, deserialized.shows)
        assertEquals(original.filters, deserialized.filters)
    }

    @Test
    fun `roundtrip with multiple lib entries`() {
        val original = DesignDocument(
            id = "_design/test",
            lib = mapOf(
                "module1" to "exports.fn1 = function() {}",
                "module2" to "exports.fn2 = function() {}"
            )
        )

        val deserialized = mapper.readValue(mapper.writeValueAsString(original), DesignDocument::class.java)

        assertEquals(original.lib, deserialized.lib)
        assertTrue(deserialized.views.isEmpty())
    }
}
