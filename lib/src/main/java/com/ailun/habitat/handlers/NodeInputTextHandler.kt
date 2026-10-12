package com.ailun.habitat.handlers

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.ShellQuote
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IAccessibilityProvider
import com.ailun.habitat.api.IShellExecutor
import com.ailun.habitat.paramBool

/**
 * [ACTION_INPUT_TEXT] : 在输入框中输入文本。
 *
 * params:
 * - `text` (必需): 要输入的文本内容，支持 `${var}` 变量插值
 * - `mode` (可选, 默认 "a11y"): 输入模式
 *   - "a11y": 通过无障碍服务的 ACTION_SET_TEXT 输入；没生效时退回剪贴板粘贴（中文也能输）
 *   - "shell": 通过 `input text` shell 命令输入
 *   - "ime": 通过 shell `input text` 命令输入（与 shell 相同）
 * - `target` (可选, a11y 模式): `"x,y"`，写入包含该点的输入框（先让它获得焦点）。
 *   不传时写入当前焦点输入框。QQ/微信等应用的输入框点过之后常常不报告焦点，按坐标找节点才写得进去。
 * - `submit` (可选, 默认 false): 输入后在输入框触发 IME 回车（搜索/发送/确认）。
 *   走无障碍 ACTION_IME_ENTER（Android 11+），不需要 Shizuku；不可用时退回 shell `input keyevent 66`。
 *   `text` 为空且 submit=true 时只提交，不改内容。
 * - `allow_empty` (可选, 默认 false): 允许把输入框整框设为空串（用于清空）。
 *
 * 输出变量:
 * - `input_success` (Boolean): 输入是否成功（a11y 模式会读回输入框核对内容）
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
        val target = InputTarget.parsePoint(node.params?.get("target")?.toString()?.let { context.interpolate(it) })

        Log.d(TAG, "InputText: mode=$mode, text length=${text.length}, submit=$submit, target=${target != null}")

        // 只提交不改内容：text 为空且没要求清空
        val typed = if (text.isEmpty() && !allowEmpty) {
            true
        } else {
            when (mode) {
                MODE_SHELL, MODE_IME -> inputViaShell(text)
                MODE_A11Y -> inputViaAccessibility(text, target, context.appContext)
                else -> {
                    Log.w(TAG, "InputText: unknown mode '$mode', falling back to a11y")
                    inputViaAccessibility(text, target, context.appContext)
                }
            }
        }
        val success = typed && (!submit || submitFocused(target))

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
     * 无障碍输入：ACTION_SET_TEXT，读回核对；没生效时全选 + 剪贴板粘贴。
     * 只认"真的写进去了"——QQ/微信的自定义输入框上 SET_TEXT 常返回 true 但内容不变。
     */
    private fun inputViaAccessibility(text: String, target: Pair<Int, Int>?, appContext: Context): Boolean {
        val service = provider?.getService() ?: run {
            Log.e(TAG, "InputText: Accessibility service not available")
            return false
        }
        val input = findInput(service, target) ?: run {
            Log.w(TAG, "InputText: no input field found (target=${target != null})")
            return false
        }
        return try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "InputText: ACTION_SET_TEXT result=$set")
            if (set && readBack(input, text)) return true
            if (text.isEmpty()) return set
            // 退回粘贴：先全选已有内容，粘贴即替换
            pasteInto(input, text, appContext) && readBack(input, text)
        } catch (e: Exception) {
            Log.e(TAG, "InputText: a11y input failed", e)
            false
        } finally {
            @Suppress("DEPRECATION")
            runCatching { input.recycle() }
        }
    }

    /** 读回输入框内容核对。读不到（refresh 失败）时按 SET_TEXT 的返回值算，不误判失败。 */
    private fun readBack(input: AccessibilityNodeInfo, text: String): Boolean {
        if (!input.refresh()) return true
        return InputTarget.applied(text, input.text)
    }

    private fun pasteInto(input: AccessibilityNodeInfo, text: String, appContext: Context): Boolean {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("habitat_input", text))
            val length = input.text?.length ?: 0
            if (length > 0) {
                val sel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
                }
                input.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            }
            val ok = input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.d(TAG, "InputText: ACTION_PASTE result=$ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "InputText: paste failed", e)
            false
        }
    }

    /**
     * 要写入的输入框：给了 [target] 就找包含该点的最小可编辑节点并让它获得焦点；
     * 否则（或没找到）用当前焦点输入框。
     */
    private fun findInput(service: AccessibilityService, target: Pair<Int, Int>?): AccessibilityNodeInfo? {
        if (target != null) {
            nodeAt(service, target.first, target.second)?.let { node ->
                if (!node.isFocused) {
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
                return node
            }
        }
        return try {
            service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            Log.e(TAG, "InputText: failed to query focused input", e)
            null
        }
    }

    /** 所有应用窗口里包含 (x,y) 的可编辑节点，取面积最小的。不看输入法和系统窗口。 */
    private fun nodeAt(service: AccessibilityService, x: Int, y: Int): AccessibilityNodeInfo? {
        val roots = try {
            service.windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .ifEmpty { listOfNotNull(service.rootInActiveWindow) }
        } catch (e: Exception) {
            listOfNotNull(service.rootInActiveWindow)
        }
        val found = mutableListOf<AccessibilityNodeInfo>()
        val visited = IntArray(1)
        roots.forEach { collectEditable(it, found, 0, visited) }
        val rects = found.map { n -> Rect().also { n.getBoundsInScreen(it) }.let { intArrayOf(it.left, it.top, it.right, it.bottom) } }
        val best = InputTarget.smallestContaining(rects, x, y)
        @Suppress("DEPRECATION")
        found.forEachIndexed { i, n -> if (i != best) runCatching { n.recycle() } }
        return found.getOrNull(best)
    }

    private fun collectEditable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int, visited: IntArray) {
        if (depth > MAX_DEPTH || visited[0] > MAX_NODES) return
        visited[0]++
        if (node.isEditable && node.isVisibleToUser && !node.isPassword) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditable(child, out, depth + 1, visited)
            @Suppress("DEPRECATION")
            runCatching { child.recycle() }
        }
    }

    /**
     * 在输入框触发 IME 回车。优先无障碍 ACTION_IME_ENTER（不依赖 Shizuku），
     * 节点不支持或系统低于 Android 11 时退回 shell keyevent。
     */
    private suspend fun submitFocused(target: Pair<Int, Int>?): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val service = provider?.getService()
            val focused = service?.let { findInput(it, target) }
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
        private const val MAX_DEPTH = 30
        private const val MAX_NODES = 600
    }
}
