package com.ailun.habitat.handlers

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * [ACTION_APP_SEARCH]：搜索已安装的应用。
 *
 * params：
 * - `query`（可选）：文本过滤（匹配包名或应用名），留空返回所有应用
 * - `include_system`（可选）：是否包含系统应用，默认 false
 * - `output_var`（可选）：存储结果的变量名，默认 app_list
 * - `max_results`（可选）：最大返回数量，默认 100
 * - `sort_by`（可选）：排序方式 "name" / "package" / "install_time"，默认 "name"
 */
class NodeAppSearchHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val params = node.params ?: return node.next

        val rawQuery = params["query"]?.toString()?.trim()
        val query = rawQuery?.let { context.interpolate(it) }?.lowercase()
        val includeSystem = params["include_system"]?.toString()?.equals("true", true) == true
        val outputVar = params["output_var"]?.toString()?.trim()?.ifEmpty { null }
            ?: "app_list"
        val maxResults = (params["max_results"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 100
        val sortBy = params["sort_by"]?.toString()?.trim()?.lowercase() ?: "name"

        try {
            val pm = context.appContext.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            val results = apps
                .filter { app ->
                    // Filter system apps
                    if (!includeSystem && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                        return@filter false
                    }

                    // Filter by query if provided
                    if (!query.isNullOrBlank()) {
                        val packageName = app.packageName.lowercase()
                        val appLabel = pm.getApplicationLabel(app).toString().lowercase()
                        packageName.contains(query) || appLabel.contains(query)
                    } else {
                        true
                    }
                }
                .sortedWith(Comparator { a, b ->
                    when (sortBy) {
                        "package" -> a.packageName.compareTo(b.packageName)
                        "install_time" -> {
                            val tA = try { pm.getPackageInfo(a.packageName, 0).firstInstallTime } catch (_: Exception) { 0L }
                            val tB = try { pm.getPackageInfo(b.packageName, 0).firstInstallTime } catch (_: Exception) { 0L }
                            tB.compareTo(tA) // newest first
                        }
                        else -> pm.getApplicationLabel(a).toString().lowercase()
                            .compareTo(pm.getApplicationLabel(b).toString().lowercase())
                    }
                })
                .take(maxResults)
                .map { app ->
                    JSONObject().apply {
                        put("package", app.packageName)
                        put("name", pm.getApplicationLabel(app).toString())
                        put("is_system", (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                        try {
                            val packageInfo = pm.getPackageInfo(app.packageName, 0)
                            put("version_name", packageInfo.versionName ?: "")
                            put("version_code", packageInfo.versionCode)
                        } catch (_: Exception) {
                            put("version_name", "")
                            put("version_code", 0)
                        }
                    }
                }

            val appListJson = JSONArray(results).toString()

            context.putVariable("app_list", appListJson)
            context.putVariable("app_count", results.size)
            context.putVariable("app_search_success", true)

            if (outputVar != "app_list") {
                context.putVariable(outputVar, appListJson)
            }

            Log.i(TAG, "App search: query='${query ?: ""}', found ${results.size} apps")
        } catch (e: Exception) {
            Log.e(TAG, "App search failed: ${e.message}", e)
            context.putVariable("app_search_success", false)
            context.putVariable("app_list", "[]")
            context.putVariable("app_count", 0)
            context.putVariable("app_search_error", e.message ?: "Unknown error")
        }

        return node.next
    }

    companion object {
        private const val TAG = "HabitatAppSearch"
    }
}
