package com.ailun.habitat.handlers

import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IAccessibilityProvider
import com.ailun.habitat.api.IShellExecutor
import com.ailun.habitat.paramBool
import kotlinx.coroutines.CancellationException

/**
 * [ACTION_FOREGROUND_APP]：获取当前前台应用信息。
 *
 * params：
 * - `output_var`（可选）：存储包名的变量名，默认 foreground_package
 * - `activity_var`（可选）：存储 Activity 类名的变量名，默认 foreground_activity
 * - `use_shell`（可选）：true 强制使用 shell 方式获取，默认 false
 *
 * 数据来源优先级：
 * 1. IAccessibilityProvider 提供的 foregroundPackage/foregroundActivity
 * 2. Shell 命令 dumpsys activity activities / dumpsys window
 */
class NodeForegroundAppHandler(
    private val provider: IAccessibilityProvider? = null,
    private val shellExecutor: IShellExecutor? = null
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val params = node.params ?: return node.next

        val outputVar = params["output_var"]?.toString()?.trim()?.ifEmpty { null }
            ?: "foreground_package"
        val activityVar = params["activity_var"]?.toString()?.trim()?.ifEmpty { null }
            ?: "foreground_activity"
        val forceShell = paramBool(node, context, "use_shell", false)

        var packageName: String? = null
        var activityName: String? = null

        if (!forceShell && provider != null) {
            // Try accessibility provider first
            packageName = provider.foregroundPackage
            activityName = provider.foregroundActivity
            if (packageName != null) {
                Log.d(TAG, "Got foreground app from AccessibilityProvider: $packageName / $activityName")
            }
        }

        // provider 缺失、或 provider 存在但拿不到包名（无障碍未连接）都回退 shell
        if (packageName == null) {
            // Fallback to shell
            val shell = shellExecutor
            if (shell != null) {
                try {
                    // Method 1: dumpsys activity activities
                    val activityOutput = shell.exec("dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|mFocusedActivity' | head -1")
                    if (activityOutput.isNotBlank()) {
                        val parts = parseActivityDumpsysLine(activityOutput.trim())
                        if (parts != null) {
                            packageName = parts.first
                            activityName = parts.second
                            Log.d(TAG, "Got foreground app from dumpsys activity: $packageName / $activityName")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Log.d(TAG, "dumpsys activity method failed, trying alternative")
                }

                if (packageName == null) {
                    try {
                        // Method 2: dumpsys window
                        val windowOutput = shell.exec("dumpsys window 2>/dev/null | grep mCurrentFocus | head -1")
                        if (windowOutput.isNotBlank()) {
                            val parts = parseWindowDumpsysLine(windowOutput.trim())
                            if (parts != null) {
                                packageName = parts.first
                                activityName = parts.second
                                Log.d(TAG, "Got foreground app from dumpsys window: $packageName / $activityName")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Log.d(TAG, "dumpsys window method failed")
                    }
                }

                if (packageName == null) {
                    try {
                        // Method 3: dumpsys activity recents
                        val recentsOutput = shell.exec("dumpsys activity recents 2>/dev/null | grep 'Recent #0' | head -1")
                        if (recentsOutput.isNotBlank()) {
                            packageName = parseRecentsLine(recentsOutput.trim())
                            Log.d(TAG, "Got foreground app from dumpsys recents: $packageName")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Log.d(TAG, "dumpsys recents method failed")
                    }
                }
            } else {
                // No shell executor, try Runtime.exec
                try {
                    val output = LocalShell.sh(
                        "dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity' | head -1", 10_000,
                    ).trim()

                    if (output.isNotBlank() && !output.startsWith("Error:")) {
                        val parts = parseActivityDumpsysLine(output)
                        if (parts != null) {
                            packageName = parts.first
                            activityName = parts.second
                            Log.d(TAG, "Got foreground app from Runtime.exec: $packageName / $activityName")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "Runtime.exec method failed: ${e.message}")
                }
            }
        }

        if (packageName != null) {
            context.putVariable(outputVar, packageName)
            context.putVariable("foreground_package", packageName)
            context.putVariable("foreground_success", true)
            Log.i(TAG, "Foreground app: $packageName")
        } else {
            context.putVariable(outputVar, "unknown")
            context.putVariable("foreground_package", "unknown")
            context.putVariable("foreground_success", false)
            Log.w(TAG, "Could not determine foreground app")
        }

        if (activityName != null) {
            context.putVariable(activityVar, activityName)
            context.putVariable("foreground_activity", activityName)

            // Extract short class name
            val shortClass = activityName.substringAfterLast(".").ifEmpty { activityName }
            context.putVariable("foreground_class", shortClass)
        } else {
            context.putVariable(activityVar, "unknown")
            context.putVariable("foreground_activity", "unknown")
            context.putVariable("foreground_class", "unknown")
        }

        return node.next
    }

    /**
     * Parse a dumpsys activity line like:
     * "mResumedActivity: ActivityRecord{abc123 u0 com.example.app/.MainActivity t123}"
     */
    private fun parseActivityDumpsysLine(line: String): Pair<String, String>? {
        // Extract package/activity after the last whitespace before the 't' flag
        val regex = Regex("""(\S+/\S+)\s+t\d+""")
        val match = regex.find(line) ?: return null
        val full = match.groupValues[1] // e.g., "com.example.app/.MainActivity"

        val slashIndex = full.indexOf("/")
        if (slashIndex < 0) return null

        val pkg = full.substring(0, slashIndex)
        val actPart = full.substring(slashIndex + 1)

        // If activity starts with ".", prepend package name
        val activity = if (actPart.startsWith(".")) "$pkg$actPart" else actPart

        return Pair(pkg, activity)
    }

    /**
     * Parse a dumpsys window line like:
     * "mCurrentFocus=Window{abc123 u0 com.example.app/com.example.app.MainActivity}"
     */
    private fun parseWindowDumpsysLine(line: String): Pair<String, String>? {
        val regex = Regex("""(\S+)/(\S+)}}""")
        val match = regex.find(line) ?: run {
            // Try alternative format
            val altRegex = Regex("""u0\s+(\S+)/(\S+)}""")
            altRegex.find(line)
        } ?: return null

        val pkg = match.groupValues[1]
        val activity = match.groupValues[2]
        return Pair(pkg, activity)
    }

    /**
     * Parse a dumpsys recents line like:
     * "Recent #0: TaskInfo{... type=home} ..."
     */
    private fun parseRecentsLine(line: String): String? {
        // Try to extract package info from the recents line
        val regex = Regex("""component=ComponentInfo\{(\S+)/(\S+)}""")
        val match = regex.find(line) ?: return null
        return match.groupValues[1]
    }

    companion object {
        private const val TAG = "HabitatForeground"
    }
}
