package com.ailun.habitat.app

import com.ailun.habitat.NodeHandlerFactory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteToolPolicyTest {

    private val dangerous = setOf(NodeHandlerFactory.ACTION_SHELL)

    @Test
    fun workspaceDeleteDeniedByDefault() {
        val ws = NodeHandlerFactory.ACTION_WORKSPACE
        assertNotNull(RemoteToolPolicy.check(ws, "delete", dangerous, dangerousAllowed = false))
        assertNotNull(RemoteToolPolicy.check(ws, " DELETE ", dangerous, dangerousAllowed = false))
        assertNull(RemoteToolPolicy.check(ws, "delete", dangerous, dangerousAllowed = true))
        listOf("write", "append", "read", "list", null).forEach {
            assertNull("远程可以 $it", RemoteToolPolicy.check(ws, it, dangerous, dangerousAllowed = false))
        }
    }

    @Test
    fun wholeToolStillDenied() {
        assertNotNull(RemoteToolPolicy.check(NodeHandlerFactory.ACTION_SHELL, null, dangerous, dangerousAllowed = false))
        assertNull(RemoteToolPolicy.check(NodeHandlerFactory.ACTION_MINI_SCREEN, "show", dangerous, dangerousAllowed = false))
    }
}
