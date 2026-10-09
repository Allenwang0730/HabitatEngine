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
import com.ailun.habitat.paramBool

/**
 * [ACTION_INPUT_TEXT] : 在当前焦点输入框中输入文本。
 *
 * params:
 * - `text` (必需): 要输入的文本内容，支持 `${var}` 变量插值
 * - `mode` (可选, 默认 "a11y"): 输入模式
 *   - "a11y": 通过无障碍服务的 ACTION_SET_TEXT 在焦点节点输入
 *   - "shell": 通过 `input text` shell 命令输入
 *   - "ime": 通过 shell `input text` 命令输入（与 shell 相同）
 * - `submit` (可选, 默认 false): 输入后在焦点框触发 IME 回车（搜索/发送/确认）。
 *   走无障碍 ACTION_IME_ENTER（Android 11+），不需要 Shizuku；不可用时退回 shell `input keyevent 66`。
 *   `text` 为空且 submit=true 时只提交，不改内容。
 * - `allow_empty` (可选, 默认 false): 允许把输入框整框设为空串（用于清空）。
 *
 * 输出变量:
 * - `input_success` (Boolean): 输入是否成功
 */
class NodeInputTextHandler(
    private val provider: IAccessibilityProvider?,
    private val shellExecutor: IShellExecutor?,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("input_success", false)
        val rawText = node.params?.get("text")?.toString().orEmpty()
        val submit = paramBool(node, context, "submit", false)
        val allowEmpty = paramBool(node, context, "allow_empty", false)
        if (rawText.isEmpty() && !submit && !allowEmpty) {
            Log.w(TAG, "InputText: 'text' parameter is empty")
            return node.next
        }

        val text = context.interpolate(rawText)
        val mode = node.params?.get("mode")?.toString()?.trim()?.lowercase() ?: MODE_A11Y

        Log.d(TAG, "InputText: mode=$mode, text length=${text.length}, submit=$submit")

        // 只提交不改内容：text 为空且没要求清空
        val typed = if (text.isEmpty() && !allowEmpty) {
            true
        } else {
            when (mode) {
                MODE_SHELL, MODE_IME -> inputViaShell(text)
                MODE_A11Y -> inputViaAccessibility(text)
                else -> {
                    Log.w(TAG, "InputText: unknown mode '$mode', falling back to a11y")
                    inputViaAccessibility(text)
                }
            }
        }
        val success = typed && (!submit || submitFocused())

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

    /**
     * 在焦点输入框触发 IME 回车。优先无障碍 ACTION_IME_ENTER（不依赖 Shizuku），
     * 节点不支持或系统低于 Android 11 时退回 shell keyevent。
     */
    private suspend fun submitFocused(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val service = provider?.getService()
            val focused = try {
                service?.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } catch (e: Exception) {
                null
            }
            if (focused != null) {
                try {
                    val ime = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                    if (focused.actionList.any { it.id == ime.id } && focused.performAction(ime.id)) {
                        Log.d(TAG, "InputText: ACTION_IME_ENTER ok")
                        return true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "InputText: ACTION_IME_ENTER failed", e)
                } finally {
                    @Suppress("DEPRECATION")
                    runCatching { focused.recycle() }
                }
            }
        }
        val executor = shellExecutor ?: return false
        return try {
            val out = executor.exec("input keyevent 66")
            !out.contains("Error", ignoreCase = true) && !out.contains("exception", ignoreCase = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "InputText: keyevent submit failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "HabitatInputText"
        const val MODE_A11Y = "a11y"
        const val MODE_SHELL = "shell"
        const val MODE_IME = "ime"
    }
}
