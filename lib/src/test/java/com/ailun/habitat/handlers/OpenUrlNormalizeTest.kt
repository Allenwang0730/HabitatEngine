package com.ailun.habitat.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenUrlNormalizeTest {

    private fun n(s: String) = NodeOpenUrlHandler.normalize(s)

    @Test
    fun acceptsHttpAndHttps() {
        assertEquals("https://example.com/a?b=1#c", n("https://example.com/a?b=1#c"))
        assertEquals("http://example.com", n("  http://example.com  "))
        assertEquals("https://Example.com/X", n("HTTPS://Example.com/X"))
    }

    @Test
    fun addsHttpsWhenSchemeMissing() {
        assertEquals("https://www.baidu.com", n("www.baidu.com"))
        assertEquals("https://example.com:8080/x", n("example.com:8080/x"))
        assertEquals("https://m.jd.com/item/1.html", n("m.jd.com/item/1.html"))
    }

    @Test
    fun rejectsOtherSchemesAndCredentials() {
        assertNull(n("javascript:alert(1)"))
        assertNull(n("file:///sdcard/a.html"))
        assertNull(n("content://com.android.contacts/contacts"))
        assertNull(n("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(n("tel:10086"))
        assertNull(n("https://user:pass@example.com"))
        assertNull(n("https://"))
        assertNull(n(""))
        assertNull(n("https://exa mple.com"))
        assertNull(n("https://example.com/" + "a".repeat(3000)))
    }
}
