package com.ailun.habitat.handlers

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.ShellQuote
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IAccessibilityProvider
import com.ailun.habitat.api.IShellExecutor

/**
 * [ACTION_INPUT_TEXT] : 在当前焦点输入框中输入文本。
 *
 * params:
 * - `text` (必需): 要输入的文本内容，支持 `${var}` 变量插值
 * - `mode` (可选, 默认 "a11y"): 输入模式
 *   - "a11y": 通过无障碍服务的 ACTION_SET_TEXT 在焦点节点输入
 *   - "shell": 通过 `input text` shell 命令输入
 *   - "ime": 通过 shell `input text` 命令输入（与 shell 相同）
 *
 * 输出变量:
 * - `input_success` (Boolean): 输入是否成功
 */
class NodeInputTextHandler(
    private val provider: IAccessibilityProvider?,
    private val shellExecutor: IShellExecutor?,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawText = node.params?.get("text")?.toString().orEmpty()
        if (rawText.isEmpty()) {
            Log.w(TAG, "InputText: 'text' parameter is empty")
            context.putVariable("input_success", false)
            return node.next
        }

        val text = context.interpolate(rawText)
        val mode = node.params?.get("mode")?.toString()?.trim()?.lowercase() ?: MODE_A11Y

        Log.d(TAG, "InputText: mode=$mode, text length=${text.length}")

        val success = when (mode) {
            MODE_SHELL, MODE_IME -> inputViaShell(text)
            MODE_A11Y -> inputViaAccessibility(text)
            else -> {
                Log.w(TAG, "InputText: unknown mode '$mode', falling back to a11y")
                inputViaAccessibility(text)
            }
        }

        context.putVariable("input_success", success)
        if (success) {
            context.log("InputText: text input successful (mode=$mode)")
        } else {
            context.log("InputText: text input failed (mode=$mode)")
        }

        return node.next
    }

    /**
     * 通过 shell `input text` 命令输入文本。
     * 对单引号进行转义以防止 shell 解析错误。
     */
    private suspend fun inputViaShell(text: String): Boolean {
        val executor = shellExecutor ?: run {
            Log.e(TAG, "InputText: IShellExecutor not available")
            return false
        }
        return try {
            val output = executor.exec("input text ${ShellQuote.quote(text)}")
            !output.contains("Error", ignoreCase = true) &&
                !output.contains("exception", ignoreCase = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "InputText: shell execution failed", e)
            false
        }
    }

    /**
     * 通过无障碍服务的 ACTION_SET_TEXT 在焦点输入节点中输入文本。
     */
    private fun inputViaAccessibility(text: String): Boolean {
        val service = provider?.getService() ?: run {
            Log.e(TAG, "InputText: Accessibility service not available")
            return false
        }

        val focusedNode: AccessibilityNodeInfo? = try {
            service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            Log.e(TAG, "InputText: failed to query rootInActiveWindow", e)
            null
        }

        if (focusedNode == null) {
            Log.w(TAG, "InputText: no focused input field found in active window")
            return false
        }

        return try {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "InputText: ACTION_SET_TEXT result=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "InputText: ACTION_SET_TEXT failed", e)
            false
        } finally {
            focusedNode.recycle()
        }
    }

    companion object {
        private const val TAG = "HabitatInputText"
        const val MODE_A11Y = "a11y"
        const val MODE_SHELL = "shell"
        const val MODE_IME = "ime"
    }
}
