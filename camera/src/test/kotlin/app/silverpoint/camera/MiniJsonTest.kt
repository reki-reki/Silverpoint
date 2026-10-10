package app.silverpoint.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {
    @Test
    fun encodesScalarsAndEscapes() {
        val json = MiniJson.encode(mapOf("a" to 1, "b" to "x\"y\n", "c" to null, "d" to true, "e" to 1.5), pretty = false)
        assertEquals("""{"a":1,"b":"x\"y\n","c":null,"d":true,"e":1.5}""", json)
    }

    @Test
    fun wholeDoublesHaveNoDecimalPoint() {
        assertEquals("""{"mp":12}""", MiniJson.encode(mapOf("mp" to 12.0), pretty = false))
    }

    @Test
    fun nonFiniteNumbersBecomeNull() {
        assertEquals("[null, null]", MiniJson.encode(listOf(Double.NaN, Double.POSITIVE_INFINITY)))
    }

    @Test
    fun primitiveArraysAndSetsBecomeLists() {
        assertEquals("""{"x":[1,2,3],"y":["a"]}""", MiniJson.encode(mapOf("x" to intArrayOf(1, 2, 3), "y" to setOf("a")), false))
    }

    @Test
    fun emptyContainers() {
        assertEquals("""{"m":{},"l":[]}""", MiniJson.encode(mapOf("m" to emptyMap<String, Any?>(), "l" to emptyList<Any>()), false))
    }

    @Test
    fun prettyKeepsScalarListsOnOneLine() {
        val json = MiniJson.encode(mapOf("sizes" to listOf("1x1", "2x2")))
        assertTrue(json, json.contains("\"sizes\": [\"1x1\", \"2x2\"]"))
    }

    @Test
    fun controlCharactersAreEscaped() {
        assertEquals("\"\\u0001\"", MiniJson.encode("\u0001"))
    }
}
