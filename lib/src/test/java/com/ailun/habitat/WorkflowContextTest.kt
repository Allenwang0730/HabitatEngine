package com.ailun.habitat

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowContextTest {

    private val ctx = WorkflowContext.detached()

    @Test
    fun replacesVariables() {
        ctx.variables["a"] = 1
        ctx.variables["name"] = "张三"
        assertEquals("1-张三", ctx.interpolate("\${a}-\${name}"))
    }

    @Test
    fun undefinedIsNullString() {
        assertEquals("x=null", ctx.interpolate("x=\${missing}"))
    }

    @Test
    fun replacedValuesAreNotExpandedAgain() {
        ctx.variables["secret"] = "TOKEN"
        ctx.variables["sms"] = "hi \${secret}"
        assertEquals("msg: hi \${secret}", ctx.interpolate("msg: \${sms}"))
    }

    @Test
    fun singlePassOnly() {
        ctx.variables["inner"] = "b"
        ctx.variables["ab"] = "X"
        // 不做嵌套展开：\${a\${inner}} 中只有 \${inner} 被替换
        assertEquals("\${ab}", ctx.interpolate("\${a\${inner}}"))
    }

    @Test
    fun dottedNameIsLiteralKey() {
        ctx.variables["a.b"] = "dot"
        ctx.variables["a"] = mapOf("b" to "path")
        assertEquals("dot", ctx.interpolate("\${a.b}"))
    }

    @Test
    fun textWithoutPatternUnchanged() {
        assertEquals("plain \$ { x }", ctx.interpolate("plain \$ { x }"))
        assertEquals("\${not closed", ctx.interpolate("\${not closed"))
    }
}
