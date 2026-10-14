package com.ailun.habitat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkflowRepositoryParseTest {

    @Test
    fun filtersNullRequiredFieldsAndFillsDescription() {
        val list = WorkflowRepository.parse(
            """[{"id":"a","name":"A","jsonContent":"{}"},
                {"id":"b","jsonContent":"{}"},
                null,
                {"id":"c","name":"C","description":"d","jsonContent":"{}"}]"""
        )!!
        assertEquals(listOf("a", "c"), list.map { it.id })
        assertEquals("", list[0].description)
    }

    @Test
    fun itemLevelRecoverySkipsBadEntries() {
        val raw = """[{"id":"a","name":"A","jsonContent":"{}"}, 5, {"id":"b","name":["x"],"jsonContent":"{}"}]"""
        assertNull(WorkflowRepository.parse(raw))
        assertEquals(listOf("a"), WorkflowRepository.parseItems(raw)!!.map { it.id })
    }

    @Test
    fun totallyBrokenReturnsNull() {
        assertNull(WorkflowRepository.parse("not json {"))
        assertNull(WorkflowRepository.parseItems("not json {"))
        assertNull(WorkflowRepository.parseItems("""{"id":"a"}"""))
    }
}
