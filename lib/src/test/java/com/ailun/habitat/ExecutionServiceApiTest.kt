package com.ailun.habitat

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公开 API 形状的编译期回归：README 里的写法必须能编译。
 * [apiShapes] 不会被调用——只要这个文件能编译，签名就没被改坏。
 */
class ExecutionServiceApiTest {

    @Suppress("unused")
    private fun apiShapes(context: Context, factory: NodeHandlerFactory) {
        // 结尾 lambda 对应 onResult（变量快照）
        HabitatExecutionService.start("id", "{}", context, factory) { vars: Map<String, Any?> -> vars.size }
        // README 的具名写法
        HabitatExecutionService.start(
            "id", "{}", context, factory,
            onLog = { line: String -> line.length },
            onResult = { vars -> vars.size },
        )
        // origin 可以具名插在中间
        HabitatExecutionService.start("id", "{}", context, factory, origin = WorkflowContext.workflowOrigin("x")) { }
    }

    @Test
    fun compiles() {
        assertTrue(true)
    }
}
