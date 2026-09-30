package com.ailun.habitat.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.NodeModule
import com.ailun.habitat.app.bridge.AppAccessibilityProvider
import com.ailun.habitat.selftest.NodeSelfTestRunner
import com.ailun.habitat.selftest.NodeSelfTests
import com.ailun.habitat.selftest.SelfTestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 节点自测 Activity（阶段 A · 自跑测试架，宿主在 Habitat app）。
 *
 * adb 驱动：
 *   adb shell am start -n com.ailun.habitat/.app.NodeSelfTestActivity --ez auto true
 *
 * 入口受 android.permission.DUMP 保护（adb shell 持有，普通 App 无法拿到），仅供 adb 调试使用。
 *
 * 行为：
 * - 依次跑完 [NodeSelfTests] 全部用例（无障碍未连接时相关用例 SKIP）
 * - 结果写入 filesDir/test_results.json（adb 用 run-as 读取）
 * - 每条结果输出 Logcat（tag=HabitatSelfTest），结尾打 HABITAT_TEST_DONE 标记
 * - auto=true 时跑完自动 finish（供 adb 脚本轮询）；否则留在界面展示结果
 *
 * 注：SmartCS 仓库完全不动；两份 :lib 在测试架期间暂不同步（见 ENGINE_MASTER_PLAN 阶段 A）。
 */
class NodeSelfTestActivity : Activity() {

    companion object {
        private const val TAG = "HabitatSelfTest"
        private const val MARKER_DONE = "HABITAT_TEST_DONE"
    }

    private lateinit var statusView: TextView
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onDestroy() {
        testScope.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply { textSize = 13f }
        setContentView(statusView)

        val auto = intent?.getBooleanExtra("auto", false) ?: false
        val only = intent?.getStringExtra("only") // 可选：只跑指定名称的用例

        // 调试用：前台 Activity 启动 Agent 服务（Android 14+ 后台启动受限，由界面代启），启完即退
        if (intent?.getBooleanExtra("start_agent", false) == true) {
            AgentService.start(this)
            Log.i("HabitatAgent", "AgentService started via activity")
            finish()
            return
        }

        testScope.launch {
            // shell=null → ACTION_SHELL 走 Runtime.exec 兜底（与 SmartCS 调试页一致，结果可复现）
            val factory = NodeHandlerFactory(AppAccessibilityProvider, shell = null)
            val cases = NodeSelfTests.all().filter { only == null || it.name == only }
            val a11yConnected = AppAccessibilityProvider.getService() != null
            Log.i(TAG, "=== NodeSelfTest start: ${cases.size} cases, a11yConnected=$a11yConnected ===")

            val results = NodeSelfTestRunner.run(
                context = applicationContext,
                factory = factory,
                cases = cases,
                accessibilityConnected = a11yConnected,
            ) { line -> Log.i(TAG, "WF: $line") }

            // 模块化冒烟（阶段 B）：受限 factory 只注册 CORE；INTERACTION 节点应缺失
            val moduleSmoke = runCatching {
                val coreFactory = NodeHandlerFactory(AppAccessibilityProvider, shell = null, modules = setOf(NodeModule.CORE))
                val coreOk = coreFactory.get(NodeHandlerFactory.CONDITION_SWITCH) != null &&
                    coreFactory.get(NodeHandlerFactory.ACTION_MATH) != null
                val interactionAbsent = coreFactory.get(NodeHandlerFactory.ACTION_CLICK) == null
                val fullSize = factory.size
                "moduleSmoke: CORE可用=$coreOk INTERACTION缺失=$interactionAbsent 全量大小=$fullSize CORE大小=${coreFactory.size}"
            }.getOrElse { "moduleSmoke FAILED: ${it.message}" }
            Log.i(TAG, "RESULT ${if (moduleSmoke.contains("FAILED")) "FAIL" else "PASS"} 模块化冒烟 (0ms) $moduleSmoke")

            val passCount = results.count { it.status == "PASS" }
            val failCount = results.count { it.status == "FAIL" }
            val skipCount = results.count { it.status == "SKIP" }

            // 结果文件（adb: run-as com.ailun.habitat cat files/test_results.json）
            runCatching {
                val file = File(filesDir, "test_results.json")
                file.writeText(buildResultsJson(results).toString(2))
                Log.i(TAG, "results written: ${file.absolutePath}")
            }.onFailure { Log.e(TAG, "write results failed: ${it.message}") }

            // Logcat 逐条 + 结束标记
            results.forEach { r ->
                Log.i(TAG, "RESULT ${r.status} ${r.name} (${r.durationMs}ms) ${r.detail}" +
                    (r.error?.let { " | $it" } ?: ""))
            }
            Log.i(TAG, "$MARKER_DONE total=${results.size} pass=$passCount fail=$failCount skip=$skipCount")

            // 界面展示（协程在 Dispatchers.Default，必须切主线程）
            val sb = StringBuilder("节点自测完成：PASS $passCount / FAIL $failCount / SKIP $skipCount\n\n")
            results.forEach { r -> sb.append("${r.status}\t${r.name}\t${r.detail}\n") }
            runOnUiThread { statusView.text = sb.toString() }

            if (auto) {
                delay(1500) // 给 Logcat 一点刷新时间
                finish()
            } else {
                runOnUiThread {
                    Toast.makeText(this@NodeSelfTestActivity, "自测完成：$passCount 通过 / $failCount 失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildResultsJson(results: List<SelfTestResult>): JSONObject {
        val arr = JSONArray()
        results.forEach { r ->
            val o = JSONObject()
            o.put("name", r.name)
            o.put("status", r.status)
            o.put("pass", r.pass)
            o.put("skipped", r.skipped)
            o.put("detail", r.detail)
            o.put("duration_ms", r.durationMs)
            r.error?.let { o.put("error", it) }
            arr.put(o)
        }
        val summary = JSONObject()
        summary.put("total", results.size)
        summary.put("pass", results.count { it.status == "PASS" })
        summary.put("fail", results.count { it.status == "FAIL" })
        summary.put("skip", results.count { it.status == "SKIP" })
        val root = JSONObject()
        root.put("generated_at", System.currentTimeMillis())
        root.put("summary", summary)
        root.put("results", arr)
        return root
    }
}
