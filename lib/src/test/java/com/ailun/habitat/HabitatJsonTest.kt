package com.ailun.habitat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HabitatJsonTest {

    @Test
    fun numbersAreNarrowed() {
        val g = HabitatJson.fromJson(
            """{"start_node_id":"a","nodes":{"a":{"id":"a","type":"T","params":
               {"i":5,"neg":-7,"l":3000000000,"big":9007199254740993,"d":1.5,"e":1e3}}}}"""
        )
        val p = g.nodes!!["a"]!!.params!!
        assertEquals(5, p["i"])
        assertEquals(-7, p["neg"])
        assertEquals(3000000000L, p["l"])
        // > 2^53：经 Double 会变成 ...992
        assertEquals(9007199254740993L, p["big"])
        assertEquals(1.5, p["d"])
        assertEquals(1000, p["e"])
    }

    @Test
    fun nonObjectNodesAreFiltered() {
        val g = HabitatJson.fromJson(
            """{"start_node_id":"a","nodes":{"_comment":"说明","sep":1,"a":{"id":"a","type":"T"}}}"""
        )
        assertEquals(setOf("a"), g.nodes!!.keys)
    }

    @Test
    fun missingFieldsThrow() {
        expectIae("""{"nodes":{"a":{"id":"a","type":"T"}}}""")
        expectIae("""{"start_node_id":"a"}""")
        expectIae("""{"start_node_id":"a","nodes":{"a":{"id":"a"}}}""")
        expectIae("""{"start_node_id":"a","nodes":{"a":{"type":"T"}}}""")
        expectIae("""{"start_node_id":"x","nodes":{"a":{"id":"a","type":"T"}}}""")
        expectIae("""[1,2]""")
        expectIae("""{"start_node_id":"a","nodes":{"a":""")
    }

    @Test
    fun danglingReferencesDoNotThrow() {
        val g = HabitatJson.fromJson(
            """{"start_node_id":"a","nodes":{"a":{"id":"a","type":"T","next":"missing"}}}"""
        )
        assertEquals("missing", g.nodes!!["a"]!!.next)
    }

    private fun expectIae(json: String) {
        try {
            HabitatJson.fromJson(json)
            fail("expected IllegalArgumentException for $json")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}
