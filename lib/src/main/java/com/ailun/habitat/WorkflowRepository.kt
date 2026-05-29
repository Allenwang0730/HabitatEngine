package com.ailun.habitat

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

object WorkflowRepository {

    private const val PREFS_NAME = "habitat_workflow_library"
    private const val KEY_WORKFLOWS = "workflows_json"
    private const val KEY_FLOAT_MOUNTED_WORKFLOW_ID = "float_mounted_workflow_id"
    private const val TAG = "WorkflowRepository"

    private val gson = GsonBuilder().create()
    private val lock = Any()

    fun getAll(context: Context): List<HabitatWorkflow> {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_WORKFLOWS, null)
            if (raw.isNullOrBlank()) {
                val seeded = defaultWorkflows()
                saveAllInternal(prefs, seeded)
                return seeded
            }
            val type = object : TypeToken<MutableList<HabitatWorkflow>>() {}.type
            val list: MutableList<HabitatWorkflow>? = try {
                gson.fromJson(raw, type)
            } catch (e: Exception) {
                Log.e(TAG, "解析工作流数据失败，回退到默认列表", e)
                null
            }
            return list ?: defaultWorkflows().also { saveAllInternal(prefs, it) }
        }
    }

    fun saveAll(context: Context, workflows: List<HabitatWorkflow>) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            saveAllInternal(prefs, workflows)
        }
    }

    fun upsert(context: Context, workflow: HabitatWorkflow) {
        synchronized(lock) {
            val list = getAll(context).toMutableList()
            val i = list.indexOfFirst { it.id == workflow.id }
            if (i >= 0) list[i] = workflow else list.add(workflow)
            saveAll(context, list)
        }
    }

    fun delete(context: Context, id: String) {
        synchronized(lock) {
            val list = getAll(context).filterNot { it.id == id }
            saveAll(context, list)
            if (isFloatMounted(context, id)) {
                removeFloatMountedWorkflowId(context, id)
            }
        }
    }

    fun getFloatMountedWorkflowIds(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FLOAT_MOUNTED_WORKFLOW_ID, null)
        return raw?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    fun addFloatMountedWorkflowId(context: Context, id: String) {
        val ids = getFloatMountedWorkflowIds(context).toMutableSet()
        ids.add(id)
        saveMountedIds(context, ids)
    }

    fun removeFloatMountedWorkflowId(context: Context, id: String) {
        val ids = getFloatMountedWorkflowIds(context).toMutableSet()
        ids.remove(id)
        saveMountedIds(context, ids)
    }

    fun isFloatMounted(context: Context, id: String): Boolean =
        getFloatMountedWorkflowIds(context).contains(id)

    private fun saveMountedIds(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FLOAT_MOUNTED_WORKFLOW_ID, ids.joinToString(","))
            .commit()
    }

    private fun saveAllInternal(prefs: android.content.SharedPreferences, workflows: List<HabitatWorkflow>) {
        val json = gson.toJson(workflows)
        try {
            prefs.edit()
                .putString(KEY_WORKFLOWS, json)
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "保存工作流数据失败", e)
        }
    }

    private fun defaultWorkflows(): List<HabitatWorkflow> = listOf(
        // ──────────────────────────────────────────
        //  1. 快速入门
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_hello",
            name = "你好 Habitat",
            description = "获取当前时间 → Toast 问候 → 振动反馈 — 适合初次体验",
            jsonContent = """
{"start_node_id":"s","nodes":{"s":{"id":"s","type":"ACTION_GET_TIME","params":{"format":"HH:mm:ss","output_var":"t"},"next":"toast"},"toast":{"id":"toast","type":"ACTION_TOAST","params":{"message":"Habitat 启动于 ${'$'}{t}"},"next":"v"},"v":{"id":"v","type":"ACTION_VIBRATE","params":{"duration":120,"amplitude":60}}}}
""".trimIndent(),
        ),

        // ──────────────────────────────────────────
        //  2. 变量与数学运算
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_variables",
            name = "变量与运算",
            description = "SET_VARIABLE → MATH → TEXT_OPERATION → CLIPBOARD → TOAST",
            jsonContent = """
{"start_node_id":"m1","nodes":{"m1":{"id":"m1","type":"ACTION_SET_VARIABLE","params":{"name":"a","value":42,"type":"int"},"next":"m2"},"m2":{"id":"m2","type":"ACTION_SET_VARIABLE","params":{"name":"b","value":8,"type":"int"},"next":"m3"},"m3":{"id":"m3","type":"ACTION_MATH","params":{"operation":"add","a":"${'$'}{a}","b":"${'$'}{b}","output_var":"sum"},"next":"m4"},"m4":{"id":"m4","type":"ACTION_MATH","params":{"operation":"multiply","a":"${'$'}{a}","b":"${'$'}{b}","output_var":"prod"},"next":"m5"},"m5":{"id":"m5","type":"ACTION_MATH","params":{"operation":"divide","a":"${'$'}{sum}","b":2,"output_var":"avg"},"next":"m6"},"m6":{"id":"m6","type":"ACTION_CLIPBOARD","params":{"action":"set","text":"${'$'}{a}+${'$'}{b}=${'$'}{sum}, ${'$'}{a}×${'$'}{b}=${'$'}{prod}, avg=${'$'}{avg}"},"next":"m7"},"m7":{"id":"m7","type":"ACTION_LOG","params":{"message":"计算完成: sum=${'$'}{sum}, prod=${'$'}{prod}, avg=${'$'}{avg}"},"next":"m8"},"m8":{"id":"m8","type":"ACTION_TOAST","params":{"message":"已复制到剪贴板: ${'$'}{a}+${'$'}{b}=${'$'}{sum}"}}}}
""".trimIndent(),
        ),

        // ──────────────────────────────────────────
        //  3. 屏幕感知
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_screen",
            name = "屏幕感知",
            description = "读取当前屏幕文字 → 匹配关键词 → 条件分支反馈（需无障碍服务）",
            jsonContent = """
{"start_node_id":"r1","nodes":{"r1":{"id":"r1","type":"ACTION_READ_SCREEN","params":{"keyword":"设置","context_lines":3},"next":"r2"},"r2":{"id":"r2","type":"CONDITION_SWITCH","params":{"expression":"screen_data == true"},"branches":{"true":"r3","false":"r4"}},"r3":{"id":"r3","type":"ACTION_TOAST","params":{"message":"✅ 找到关键词「设置」"},"next":null},"r4":{"id":"r4","type":"ACTION_TOAST","params":{"message":"❌ 未找到关键词「设置」"},"next":null}}}
""".trimIndent(),
        ),

        // ──────────────────────────────────────────
        //  4. Shizuku 系统控制
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_shizuku",
            name = "Shizuku 系统控制",
            description = "截屏 → 获取设备信息 → Shell 命令 → 综合系统操作（需 Shizuku）",
            jsonContent = """
{"start_node_id":"s1","nodes":{"s1":{"id":"s1","type":"ACTION_LOG","params":{"message":"开始 Shizuku 系统操作测试"},"next":"s2"},"s2":{"id":"s2","type":"ACTION_SHELL","params":{"command":"getprop ro.product.model","mode":"auto"},"next":"s3"},"s3":{"id":"s3","type":"ACTION_LOG","params":{"message":"设备型号: ${'$'}{shell_output}"},"next":"s4"},"s4":{"id":"s4","type":"ACTION_SHELL","params":{"command":"dumpsys battery | grep level","mode":"auto"},"next":"s5"},"s5":{"id":"s5","type":"ACTION_LOG","params":{"message":"电池: ${'$'}{shell_output}"},"next":"s6"},"s6":{"id":"s6","type":"ACTION_SCREENSHOT","params":{"format":"base64","quality":50,"output_var":"img"},"next":"s7"},"s7":{"id":"s7","type":"CONDITION_SWITCH","params":{"expression":"screenshot_success == true"},"branches":{"true":"s8","false":"s9"}},"s8":{"id":"s8","type":"ACTION_TOAST","params":{"message":"✅ Shizuku 测试全部通过"},"next":null},"s9":{"id":"s9","type":"ACTION_TOAST","params":{"message":"⚠️ 截屏失败，请检查 Shizuku"},"next":null}}}
""".trimIndent(),
        ),

        // ──────────────────────────────────────────
        //  5. 网络请求与 JSON 解析
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_network",
            name = "HTTP 与 JSON 解析",
            description = "GET 请求 → JSON 路径提取 → 条件判断 → Toast 显示结果",
            jsonContent = """
{"start_node_id":"h1","nodes":{"h1":{"id":"h1","type":"ACTION_HTTP_REQUEST","params":{"url":"https://httpbin.org/get","method":"GET","output_var":"resp","timeout":10000},"next":"h2"},"h2":{"id":"h2","type":"CONDITION_SWITCH","params":{"expression":"http_success == true"},"branches":{"true":"h3","false":"h5"}},"h3":{"id":"h3","type":"ACTION_PARSE_JSON","params":{"json":"${'$'}{resp}","path":"headers.Host","output_var":"host"},"next":"h4"},"h4":{"id":"h4","type":"ACTION_TOAST","params":{"message":"HTTP 请求成功\nHost: ${'$'}{host}"},"next":null},"h5":{"id":"h5","type":"ACTION_TOAST","params":{"message":"HTTP 请求失败"},"next":null}}}
""".trimIndent(),
        ),

        // ──────────────────────────────────────────
        //  6. 本地 AI 推理
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_ai",
            name = "本地 AI 推理",
            description = "准备 Prompt → ACTION_AI_CHAT → 输出结果（需加载 Gemma 模型）",
            jsonContent = """
{"start_node_id":"a1","nodes":{"a1":{"id":"a1","type":"ACTION_SET_VARIABLE","params":{"name":"q","value":"用一句话解释什么是手机自动化","type":"string"},"next":"a2"},"a2":{"id":"a2","type":"ACTION_LOG","params":{"message":"发送 Prompt: ${'$'}{q}"},"next":"a3"},"a3":{"id":"a3","type":"ACTION_AI_CHAT","params":{"prompt":"${'$'}{q}","output_var":"answer"},"next":"a4"},"a4":{"id":"a4","type":"ACTION_LOG","params":{"message":"AI 回答: ${'$'}{answer}"},"next":"a5"},"a5":{"id":"a5","type":"ACTION_TOAST","params":{"message":"AI: ${'$'}{answer}"}}}}
""".trimIndent(),
        ),

        // ──────────────────────────────────────────
        //  7. 循环控制
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_loop",
            name = "循环控制",
            description = "初始化计数器 → LOOP 计数模式 → Math 累加 → 条件判断跳回 → 完成提示",
            jsonContent = """
{"start_node_id":"lp0","nodes":{"lp0":{"id":"lp0","type":"ACTION_SET_VARIABLE","params":{"name":"sum","value":0,"type":"int"},"next":"lp1"},"lp1":{"id":"lp1","type":"ACTION_LOOP","params":{"mode":"count","times":5,"counter_var":"i","body_node":"lp2"},"next":"lp5"},"lp2":{"id":"lp2","type":"ACTION_MATH","params":{"operation":"add","a":"${'$'}{sum}","b":"${'$'}{i}","output_var":"sum"},"next":"lp3"},"lp3":{"id":"lp3","type":"ACTION_LOG","params":{"message":"第 ${'$'}{i} 次循环, sum=${'$'}{sum}"},"next":"lp4"},"lp4":{"id":"lp4","type":"ACTION_DELAY","params":{"millis":200},"next":"lp1"},"lp5":{"id":"lp5","type":"ACTION_TOAST","params":{"message":"循环完成! 1~5 累加和 = ${'$'}{sum}"}}}}
""".trimIndent(),
        ),

        // ──────────────────────────────────────────
        //  8. 通知触发器示例
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_trigger_notification",
            name = "通知触发示例",
            description = "监听所有应用通知 → 显示通知来源和内容（需开启通知监听权限）",
            jsonContent = """
{"trigger":{"type":"notification"},"start_node_id":"n1","nodes":{"n1":{"id":"n1","type":"ACTION_LOG","params":{"message":"收到通知: ${'$'}{trigger_package} - ${'$'}{trigger_title}"},"next":"n2"},"n2":{"id":"n2","type":"ACTION_TOAST","params":{"message":"通知: ${'$'}{trigger_title}\n来源: ${'$'}{trigger_package}"},"next":"n3"},"n3":{"id":"n3","type":"ACTION_CLIPBOARD","params":{"action":"set","text":"${'$'}{trigger_text}"}}}}
""".trimIndent(),
        ),

        // ──────────────────────────────────────────
        //  9. 定时触发器示例
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_trigger_timer",
            name = "定时触发示例",
            description = "每 5 秒执行一次：获取时间 → Shell 查电池 → 日志记录（自动重复）",
            jsonContent = """
{"trigger":{"type":"timer","interval_ms":5000,"repeat":true},"start_node_id":"t1","nodes":{"t1":{"id":"t1","type":"ACTION_GET_TIME","params":{"format":"HH:mm:ss","output_var":"now"},"next":"t2"},"t2":{"id":"t2","type":"ACTION_SHELL","params":{"command":"dumpsys battery | grep level","mode":"auto"},"next":"t3"},"t3":{"id":"t3","type":"ACTION_LOG","params":{"message":"[${'$'}{now}] 电池: ${'$'}{shell_output}"},"next":"t4"},"t4":{"id":"t4","type":"ACTION_TOAST","params":{"message":"[${'$'}{now}] 电池: ${'$'}{shell_output}"}}}}
""".trimIndent(),
        ),

        // ──────────────────────────────────────────
        // 10. 剪贴板触发器示例
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_trigger_clipboard",
            name = "剪贴板触发示例",
            description = "监听剪贴板变化 → 显示新内容 → 写入日志",
            jsonContent = """
{"trigger":{"type":"clipboard"},"start_node_id":"c1","nodes":{"c1":{"id":"c1","type":"ACTION_LOG","params":{"message":"剪贴板变化: ${'$'}{trigger_text}"},"next":"c2"},"c2":{"id":"c2","type":"ACTION_TEXT_OPERATION","params":{"action":"trim","input":"${'$'}{trigger_text}","output_var":"clean"},"next":"c3"},"c3":{"id":"c3","type":"ACTION_TOAST","params":{"message":"剪贴板: ${'$'}{clean}"}}}}
""".trimIndent(),
        ),

        // ──────────────────────────────────────────
        // 11. UI 交互链
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_interaction",
            name = "UI 交互链",
            description = "打开设置 → 等待加载 → 查找元素 → 全局返回键（需无障碍服务）",
            jsonContent = """
{"start_node_id":"i1","nodes":{"i1":{"id":"i1","type":"ACTION_LAUNCH_APP","params":{"package_name":"com.android.settings"},"next":"i2"},"i2":{"id":"i2","type":"ACTION_DELAY","params":{"millis":1500},"next":"i3"},"i3":{"id":"i3","type":"ACTION_FIND_ELEMENT","params":{"selector":"电池","search_mode":"text","timeout_ms":3000},"next":"i4"},"i4":{"id":"i4","type":"CONDITION_SWITCH","params":{"expression":"element_found == true"},"branches":{"true":"i5","false":"i7"}},"i5":{"id":"i5","type":"ACTION_LOG","params":{"message":"找到元素: ${'$'}{element_text}, bounds=${'$'}{element_bounds}"},"next":"i6"},"i6":{"id":"i6","type":"ACTION_DELAY","params":{"millis":800},"next":"i7"},"i7":{"id":"i7","type":"ACTION_GLOBAL_KEY","params":{"key":"back"},"next":"i8"},"i8":{"id":"i8","type":"ACTION_TOAST","params":{"message":"交互测试完成"}}}}
""".trimIndent(),
        ),

        // ──────────────────────────────────────────
        // 12. 系统状态检查
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_system_status",
            name = "系统状态检查",
            description = "依次检查 WiFi/蓝牙/网络/亮度/音量 → 汇总到日志（无需特殊权限）",
            jsonContent = """
{"start_node_id":"st1","nodes":{"st1":{"id":"st1","type":"ACTION_WIFI","params":{"action":"status"},"next":"st2"},"st2":{"id":"st2","type":"ACTION_BLUETOOTH","params":{"action":"status"},"next":"st3"},"st3":{"id":"st3","type":"ACTION_NETWORK_STATUS","params":{"action":"all"},"next":"st4"},"st4":{"id":"st4","type":"ACTION_BRIGHTNESS","params":{"action":"get"},"next":"st5"},"st5":{"id":"st5","type":"ACTION_VOLUME","params":{"action":"get","stream":"music"},"next":"st6"},"st6":{"id":"st6","type":"ACTION_LOG","params":{"message":"WiFi=${'$'}{wifi_enabled} BT=${'$'}{bluetooth_enabled} Net=${'$'}{network_type} Brightness=${'$'}{current_brightness} Vol=${'$'}{current_volume}"},"next":"st7"},"st7":{"id":"st7","type":"ACTION_TOAST","params":{"message":"WiFi:${'$'}{wifi_enabled} BT:${'$'}{bluetooth_enabled} Net:${'$'}{network_type}"}}}}
""".trimIndent(),
        ),

        // ──────────────────────────────────────────
        // 13. 文件操作
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_file_ops",
            name = "文件操作",
            description = "写入测试文件 → 读取验证 → 显示内容 → 清理删除",
            jsonContent = """
{"start_node_id":"f1","nodes":{"f1":{"id":"f1","type":"ACTION_GET_TIME","params":{"format":"yyyy-MM-dd HH:mm:ss","output_var":"ts"},"next":"f2"},"f2":{"id":"f2","type":"ACTION_FILE_OPERATION","params":{"action":"write","path":"/sdcard/habitat_demo.txt","content":"Habitat 测试文件\n创建于: ${'$'}{ts}"},"next":"f3"},"f3":{"id":"f3","type":"ACTION_FILE_OPERATION","params":{"action":"read","path":"/sdcard/habitat_demo.txt","output_var":"content"},"next":"f4"},"f4":{"id":"f4","type":"ACTION_LOG","params":{"message":"文件内容: ${'$'}{content}"},"next":"f5"},"f5":{"id":"f5","type":"ACTION_TOAST","params":{"message":"文件已写入: ${'$'}{content}"},"next":"f6"},"f6":{"id":"f6","type":"ACTION_FILE_OPERATION","params":{"action":"delete","path":"/sdcard/habitat_demo.txt"},"next":"f7"},"f7":{"id":"f7","type":"ACTION_TOAST","params":{"message":"清理完成"}}}}
""".trimIndent(),
        ),

        // ──────────────────────────────────────────
        // 14. 工具集：随机数 / Base64 / 分享 / 通知 / TTS
        // ──────────────────────────────────────────
        HabitatWorkflow(
            id = "demo_utils",
            name = "工具集演示",
            description = "RANDOM → BASE64 → SEND_NOTIFICATION → SHARE → TTS 语音播报",
            jsonContent = """
{"start_node_id":"u1","nodes":{"u1":{"id":"u1","type":"ACTION_RANDOM","params":{"min":1000,"max":9999,"output_var":"code"},"next":"u2"},"u2":{"id":"u2","type":"ACTION_BASE64","params":{"action":"encode","input":"${'$'}{code}","output_var":"encoded"},"next":"u3"},"u3":{"id":"u3","type":"ACTION_LOG","params":{"message":"随机码: ${'$'}{code} → Base64: ${'$'}{encoded}"},"next":"u4"},"u4":{"id":"u4","type":"ACTION_SEND_NOTIFICATION","params":{"title":"Habitat 工具测试","message":"随机码: ${'$'}{code}"},"next":"u5"},"u5":{"id":"u5","type":"ACTION_TOAST","params":{"message":"随机码 ${'$'}{code} 已发送通知"},"next":"u6"},"u6":{"id":"u6","type":"ACTION_TEXT_TO_SPEECH","params":{"text":"随机码是 ${'$'}{code}","language":"zh","speed":1.0},"next":null}}}
""".trimIndent(),
        ),
    )
}
