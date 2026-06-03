package org.taktik.couchdb.handlers

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.taktik.couchdb.entity.ComplexKey
import org.taktik.couchdb.entity.EmptyObjectKey

private data class EmptyObjectKeyWrapper(val key: EmptyObjectKey)

class EmptyObjectKeySerializationTest {

    private val mapper = jacksonObjectMapper().registerKotlinModule()

    // --- Serialization ---

    @Test
    fun `EmptyObjectKey serializes to empty object`() {
        assertEquals("{}", mapper.writeValueAsString(EmptyObjectKey))
    }

    @Test
    fun `EmptyObjectKey in ComplexKey serializes to empty object within array`() {
        val key = ComplexKey.of("foo", EmptyObjectKey)
        assertEquals("""["foo",{}]""", mapper.writeValueAsString(key))
    }

    @Test
    fun `EmptyObjectKey as class field serializes correctly`() {
        assertEquals("""{"key":{}}""", mapper.writeValueAsString(EmptyObjectKeyWrapper(EmptyObjectKey)))
    }

    // --- Deserialization: standalone ---

    @Nested
    inner class Standalone {
        @Test
        fun `success - empty object deserializes to the singleton`() {
            val result = mapper.readValue("{}", EmptyObjectKey::class.java)
            assertSame(EmptyObjectKey, result)
        }

        @Test
        fun `failure - string`() {
            assertThrows<MismatchedInputException> {
                mapper.readValue(""""hello"""", EmptyObjectKey::class.java)
            }
        }

        @Test
        fun `failure - number`() {
            assertThrows<MismatchedInputException> {
                mapper.readValue("42", EmptyObjectKey::class.java)
            }
        }

        @Test
        fun `failure - array`() {
            assertThrows<MismatchedInputException> {
                mapper.readValue("[]", EmptyObjectKey::class.java)
            }
        }

        @Test
        fun `failure - non-empty object`() {
            assertThrows<MismatchedInputException> {
                mapper.readValue("""{"key":"value"}""", EmptyObjectKey::class.java)
            }
        }
    }

    // --- Deserialization: class field ---

    @Nested
    inner class AsClassField {
        @Test
        fun `success - empty object deserializes to the singleton`() {
            val result = mapper.readValue("""{"key":{}}""", EmptyObjectKeyWrapper::class.java)
            assertSame(EmptyObjectKey, result.key)
        }

        @Test
        fun `failure - string`() {
            assertThrows<MismatchedInputException> {
                mapper.readValue("""{"key":"hello"}""", EmptyObjectKeyWrapper::class.java)
            }
        }

        @Test
        fun `failure - number`() {
            assertThrows<MismatchedInputException> {
                mapper.readValue("""{"key":42}""", EmptyObjectKeyWrapper::class.java)
            }
        }

        @Test
        fun `failure - array`() {
            assertThrows<MismatchedInputException> {
                mapper.readValue("""{"key":[]}""", EmptyObjectKeyWrapper::class.java)
            }
        }

        @Test
        fun `failure - non-empty object`() {
            assertThrows<MismatchedInputException> {
                mapper.readValue("""{"key":{"nested":"value"}}""", EmptyObjectKeyWrapper::class.java)
            }
        }
    }

    // --- Deserialization: ComplexKey ---

    @Nested
    inner class InComplexKey {
        @Test
        fun `success - empty object element deserializes to empty map`() {
            val result = mapper.readValue("""["foo",{}]""", ComplexKey::class.java)
            assertEquals("foo", result.components[0])
            assertEquals(emptyMap<Any, Any>(), result.components[1])
        }

        @Test
        fun `success - non-empty object element is preserved as a map`() {
            val result = mapper.readValue("""["foo",{"k":"v"}]""", ComplexKey::class.java)
            assertEquals("foo", result.components[0])
            assertEquals(mapOf("k" to "v"), result.components[1])
        }
    }
}