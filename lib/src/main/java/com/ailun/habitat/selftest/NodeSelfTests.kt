package com.ailun.habitat.selftest

/**
 * 节点自测用例目录（阶段 A 首批）。
 *
 * 覆盖"不需要物理交互"的低风险节点；需要真实通话/录音/高权限的节点单独手工测。
 * 每条用例 = 一个测试工作流 JSON + verify 断言（对执行后的变量快照做判断）。
 * 工作流统一以 "done" 节点（ACTION_SET_VARIABLE 写 wf_completed=true）结尾。
 *
 * 已知行为差异（测试时如实反映，不是用例错误）：
 * - ACTION_LOG 只输出 Logcat，不走 context.log() 回调 → LOG 用例只断言正常完成
 * - ACTION_TRY_CATCH 的 error 分支当前不可达（executor 不写 _last_error 标记）
 */
object NodeSelfTests {

    fun all(): List<SelfTestCase> = listOf(
        // ── 逻辑控制 ──
        testConditionSwitchTrue(),
        testConditionSwitchFalse(),
        testAdvancedSwitch(),
        testLoop(),
        testTryCatch(),
        testLog(),
        // ── 变量与数据 ──
        testSetVariable(),
        testTextOperation(),
        testMath(),
        testClipboard(),
        testParseJson(),
        testParseXml(),
        testBase64(),
        testFileOperation(),
        testSqlite(),
        testZip(),
        testCrypto(),
        testArray(),
        testParseDateTime(),
        // ── 工具/时间 ──
        testGetTime(),
        testRandom(),
        testDelay(),
        // ── 网络 ──
        testHttp(),
        testNetworkStatus(),
        // ── UI ──
        testToast(),
        testVibrate(),
        // ── 系统 ──
        testShell(),
        // ── 系统信息（零权限，阶段 C 新增）──
        testGetBattery(),
        testGetDeviceInfo(),
        testGetStorageInfo(),
        testPlayMedia(),
        testWaitUntilTime(),
        testGetSensor(),
        // ── 通话/系统（中风险，阶段 C 新增）──
        testGetCallState(),
        testGetSimInfo(),
        testDndMode(),
        // ── 感知（需无障碍）──
        testReadScreen(),
        testGetAppInfo(),
        testWaitForElementDisappear(),
        testWaitForElementAppearTimeout(),
        testGlobalNav(),
        // ── 高风险（需权限/物理交互，真机验收时启用）──
        testRecordAudio(),
        testCapturePhoto(),
        testGetLocation(),
        testReadNotifications(),
        // ── 界面副作用节点（放最后：SET_ALARM 会打开系统时钟）──
        testSetAlarm(),
    )

    // ── 逻辑控制 ──

    private fun testConditionSwitchTrue() = SelfTestCase(
        name = "CONDITION_SWITCH·true分支",
        description = "x=5 时 expression 'x == 5' 应走 true 分支",
        workflowJson = """
        {
          "start_node_id": "set",
          "nodes": {
            "set":  {"id":"set","type":"ACTION_SET_VARIABLE","params":{"name":"x","value":5,"type":"int"},"next":"sw"},
            "sw":   {"id":"sw","type":"CONDITION_SWITCH","params":{"expression":"x == 5"},"branches":{"true":"hit_true","false":"hit_false"}},
            "hit_true":  {"id":"hit_true","type":"ACTION_SET_VARIABLE","params":{"name":"sw_true_hit","value":true,"type":"bool"},"next":"done"},
            "hit_false": {"id":"hit_false","type":"ACTION_SET_VARIABLE","params":{"name":"sw_false_hit","value":true,"type":"bool"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["sw_true_hit"] == true && v["sw_false_hit"] == null },
    )

    private fun testConditionSwitchFalse() = SelfTestCase(
        name = "CONDITION_SWITCH·false分支",
        description = "x=5 时 expression 'x == 6' 应走 false 分支",
        workflowJson = """
        {
          "start_node_id": "set",
          "nodes": {
            "set":  {"id":"set","type":"ACTION_SET_VARIABLE","params":{"name":"x","value":5,"type":"int"},"next":"sw"},
            "sw":   {"id":"sw","type":"CONDITION_SWITCH","params":{"expression":"x == 6"},"branches":{"true":"hit_true","false":"hit_false"}},
            "hit_true":  {"id":"hit_true","type":"ACTION_SET_VARIABLE","params":{"name":"sw_true_hit","value":true,"type":"bool"},"next":"done"},
            "hit_false": {"id":"hit_false","type":"ACTION_SET_VARIABLE","params":{"name":"sw_false_hit","value":true,"type":"bool"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["sw_false_hit"] == true && v["sw_true_hit"] == null },
    )

    private fun testAdvancedSwitch() = SelfTestCase(
        name = "CONDITION_ADVANCED_SWITCH·数值比较",
        description = "score=20 时 'score > 10' 应走 true 分支",
        workflowJson = """
        {
          "start_node_id": "set",
          "nodes": {
            "set":  {"id":"set","type":"ACTION_SET_VARIABLE","params":{"name":"score","value":20,"type":"int"},"next":"sw"},
            "sw":   {"id":"sw","type":"CONDITION_ADVANCED_SWITCH","params":{"var":"score","operator":">","value":"10"},"branches":{"true":"hit_true","false":"hit_false"}},
            "hit_true":  {"id":"hit_true","type":"ACTION_SET_VARIABLE","params":{"name":"as_true_hit","value":true,"type":"bool"},"next":"done"},
            "hit_false": {"id":"hit_false","type":"ACTION_SET_VARIABLE","params":{"name":"as_false_hit","value":true,"type":"bool"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["as_true_hit"] == true && v["as_false_hit"] == null },
    )

    private fun testLoop() = SelfTestCase(
        name = "ACTION_LOOP·计数循环",
        description = "times=3 循环体应执行 3 次（末次 sum=1+3=4），随后跳出",
        workflowJson = """
        {
          "start_node_id": "loop",
          "nodes": {
            "loop":  {"id":"loop","type":"ACTION_LOOP","params":{"mode":"count","times":3,"counter_var":"i","body_node":"body"},"next":"after"},
            "body":  {"id":"body","type":"ACTION_MATH","params":{"operation":"add","a":1,"b":"${'$'}{i}","output_var":"sum"},"next":"loop"},
            "after": {"id":"after","type":"ACTION_SET_VARIABLE","params":{"name":"loop_exited","value":true,"type":"bool"},"next":"done"},
            "done":  {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["loop_exited"] == true &&
                (v["sum"] as? Number)?.toDouble() == 4.0
        },
    )

    private fun testTryCatch() = SelfTestCase(
        name = "ACTION_TRY_CATCH·成功分支",
        description = "无错误时应走 success 分支（error 分支当前不可达：executor 不写 _last_error）",
        workflowJson = """
        {
          "start_node_id": "try",
          "nodes": {
            "try": {"id":"try","type":"ACTION_TRY_CATCH","params":{},"branches":{"success":"hit_ok","error":"hit_err"}},
            "hit_ok":  {"id":"hit_ok","type":"ACTION_SET_VARIABLE","params":{"name":"tc_success_hit","value":true,"type":"bool"},"next":"done"},
            "hit_err": {"id":"hit_err","type":"ACTION_SET_VARIABLE","params":{"name":"tc_error_hit","value":true,"type":"bool"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["tc_success_hit"] == true && v["tc_error_hit"] == null },
    )

    private fun testLog() = SelfTestCase(
        name = "ACTION_LOG·日志输出",
        description = "应正常完成（LOG 只写 Logcat，不走 onLog 回调）",
        workflowJson = """
        {
          "start_node_id": "log",
          "nodes": {
            "log":  {"id":"log","type":"ACTION_LOG","params":{"message":"HABITAT_SELFTEST_LOG_MARKER"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true },
    )

    // ── 变量与数据 ──

    private fun testSetVariable() = SelfTestCase(
        name = "ACTION_SET_VARIABLE·int 类型",
        description = "name=test_val value=42 type=int → 变量应为 Int 42",
        workflowJson = """
        {
          "start_node_id": "set",
          "nodes": {
            "set":  {"id":"set","type":"ACTION_SET_VARIABLE","params":{"name":"test_val","value":42,"type":"int"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && (v["test_val"] as? Number)?.toInt() == 42 },
    )

    private fun testTextOperation() = SelfTestCase(
        name = "ACTION_TEXT_OPERATION·uppercase",
        description = "input=hello → text_output 应为 HELLO",
        workflowJson = """
        {
          "start_node_id": "op",
          "nodes": {
            "op":   {"id":"op","type":"ACTION_TEXT_OPERATION","params":{"action":"uppercase","input":"hello","output_var":"t"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["t"] == "HELLO" },
    )

    private fun testMath() = SelfTestCase(
        name = "ACTION_MATH·add",
        description = "2 + 3 → math_result 应为 5.0",
        workflowJson = """
        {
          "start_node_id": "m",
          "nodes": {
            "m":    {"id":"m","type":"ACTION_MATH","params":{"operation":"add","a":2,"b":3,"output_var":"m"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && (v["m"] as? Number)?.toDouble() == 5.0 },
    )

    private fun testClipboard() = SelfTestCase(
        name = "ACTION_CLIPBOARD·set+get 回读",
        description = "写入 habitat-selftest-42 后应能读回相同内容（需前台焦点）",
        workflowJson = """
        {
          "start_node_id": "set",
          "nodes": {
            "set":  {"id":"set","type":"ACTION_CLIPBOARD","params":{"action":"set","text":"habitat-selftest-42"},"next":"get"},
            "get":  {"id":"get","type":"ACTION_CLIPBOARD","params":{"action":"get","output_var":"cb"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["clipboard_success"] == true && v["cb"] == "habitat-selftest-42" },
    )

    private fun testParseJson() = SelfTestCase(
        name = "ACTION_PARSE_JSON·数组路径",
        description = "path=data.items[1] 应为 2",
        workflowJson = """
        {
          "start_node_id": "p",
          "nodes": {
            "p":    {"id":"p","type":"ACTION_PARSE_JSON","params":{"json":"{\"data\":{\"items\":[1,2,3]}}","path":"data.items[1]","output_var":"pj"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["parse_success"] == true && v["pj"] == "2" },
    )

    private fun testParseXml() = SelfTestCase(
        name = "ACTION_PARSE_XML·标签路径",
        description = "xpath=root.item 文本应为 Hello",
        workflowJson = """
        {
          "start_node_id": "p",
          "nodes": {
            "p":    {"id":"p","type":"ACTION_PARSE_XML","params":{"xml":"<root><item>Hello</item></root>","xpath":"root.item","output_var":"px"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["xml_success"] == true && v["px"] == "Hello" },
    )

    private fun testBase64() = SelfTestCase(
        name = "ACTION_BASE64·encode+decode 往返",
        description = "编码 Habitat Test 后解码应还原",
        workflowJson = """
        {
          "start_node_id": "enc",
          "nodes": {
            "enc":  {"id":"enc","type":"ACTION_BASE64","params":{"action":"encode","data":"Habitat Test","output_var":"b64"},"next":"dec"},
            "dec":  {"id":"dec","type":"ACTION_BASE64","params":{"action":"decode","data":"${'$'}{b64}","output_var":"plain"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["base64_success"] == true && v["plain"] == "Habitat Test" },
    )

    private fun testFileOperation() = SelfTestCase(
        name = "ACTION_FILE_OPERATION·write/read/exists",
        description = "向 filesDir 写文件后应能读回相同内容",
        workflowJson = """
        {
          "start_node_id": "w",
          "nodes": {
            "w":  {"id":"w","type":"ACTION_FILE_OPERATION","params":{"action":"write","path":"${'$'}{filesDir}/selftest.txt","content":"engine-selftest-ok"},"next":"r"},
            "r":  {"id":"r","type":"ACTION_FILE_OPERATION","params":{"action":"read","path":"${'$'}{filesDir}/selftest.txt","output_var":"fc"},"next":"e"},
            "e":  {"id":"e","type":"ACTION_FILE_OPERATION","params":{"action":"exists","path":"${'$'}{filesDir}/selftest.txt"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["file_success"] == true && v["fc"] == "engine-selftest-ok" },
    )

    // ── 数据扩展（第一批纯逻辑节点）──

    private fun testSqlite() = SelfTestCase(
        name = "ACTION_SQLITE_QUERY·建表插查",
        description = "CREATE+INSERT+SELECT 应返回 3 行",
        workflowJson = """
        {
          "start_node_id": "create",
          "nodes": {
            "create": {"id":"create","type":"ACTION_SQLITE_QUERY","params":{"database":"${'$'}{filesDir}/selftest.db","query":"CREATE TABLE IF NOT EXISTS t(x INTEGER, y TEXT)"},"next":"insert"},
            "insert": {"id":"insert","type":"ACTION_SQLITE_QUERY","params":{"database":"${'$'}{filesDir}/selftest.db","query":"INSERT INTO t(x,y) VALUES(1,'a'),(2,'b'),(3,'c')"},"next":"select"},
            "select": {"id":"select","type":"ACTION_SQLITE_QUERY","params":{"database":"${'$'}{filesDir}/selftest.db","query":"SELECT * FROM t ORDER BY x","output_var":"rows"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["sql_success"] == true &&
                ((v["sql_rows"] as? Number)?.toInt() ?: 0) == 3 &&
                (v["rows"] as? String)?.contains("\"x\":2") == true
        },
    )

    private fun testZip() = SelfTestCase(
        name = "ACTION_ZIP·压缩/列表/解压",
        description = "zip→list→unzip 应还原文件内容",
        workflowJson = """
        {
          "start_node_id": "write",
          "nodes": {
            "write": {"id":"write","type":"ACTION_FILE_OPERATION","params":{"action":"write","path":"${'$'}{filesDir}/ziptest.txt","content":"zip-selftest-content"},"next":"zip"},
            "zip":   {"id":"zip","type":"ACTION_ZIP","params":{"action":"zip","source":"${'$'}{filesDir}/ziptest.txt","dest":"${'$'}{filesDir}/ziptest.zip","output_var":"zpath"},"next":"list"},
            "list":  {"id":"list","type":"ACTION_ZIP","params":{"action":"list","source":"${'$'}{filesDir}/ziptest.zip","output_var":"zlist"},"next":"unzip"},
            "unzip": {"id":"unzip","type":"ACTION_ZIP","params":{"action":"unzip","source":"${'$'}{filesDir}/ziptest.zip","dest":"${'$'}{filesDir}/zipout","output_var":"zdir"},"next":"read"},
            "read":  {"id":"read","type":"ACTION_FILE_OPERATION","params":{"action":"read","path":"${'$'}{filesDir}/zipout/ziptest.txt","output_var":"zc"},"next":"done"},
            "done":  {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["zip_success"] == true &&
                (v["zlist"] as? String)?.contains("ziptest.txt") == true &&
                v["zc"] == "zip-selftest-content"
        },
    )

    private fun testCrypto() = SelfTestCase(
        name = "ACTION_CRYPTO·AES 加解密往返",
        description = "encrypt→decrypt 应还原明文",
        workflowJson = """
        {
          "start_node_id": "write",
          "nodes": {
            "write": {"id":"write","type":"ACTION_FILE_OPERATION","params":{"action":"write","path":"${'$'}{filesDir}/secret.txt","content":"crypto-selftest-secret"},"next":"enc"},
            "enc":   {"id":"enc","type":"ACTION_CRYPTO","params":{"action":"encrypt","path":"${'$'}{filesDir}/secret.txt","password":"selftest-pass-123","output_var":"encpath"},"next":"dec"},
            "dec":   {"id":"dec","type":"ACTION_CRYPTO","params":{"action":"decrypt","path":"${'$'}{filesDir}/secret.txt.enc","password":"selftest-pass-123","output_path":"${'$'}{filesDir}/secret.dec.txt","output_var":"decpath"},"next":"read"},
            "read":  {"id":"read","type":"ACTION_FILE_OPERATION","params":{"action":"read","path":"${'$'}{filesDir}/secret.dec.txt","output_var":"plain"},"next":"done"},
            "done":  {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["crypto_success"] == true &&
                v["plain"] == "crypto-selftest-secret"
        },
    )

    private fun testArray() = SelfTestCase(
        name = "ACTION_ARRAY·增删改查",
        description = "set/push/join/pop/get 应正确",
        workflowJson = """
        {
          "start_node_id": "set",
          "nodes": {
            "set":  {"id":"set","type":"ACTION_ARRAY","params":{"action":"set","var":"arr","value":"a,b,c","output_var":"len1"},"next":"push"},
            "push": {"id":"push","type":"ACTION_ARRAY","params":{"action":"push","var":"arr","item":"d","output_var":"len2"},"next":"join"},
            "join": {"id":"join","type":"ACTION_ARRAY","params":{"action":"join","var":"arr","delimiter":"-","output_var":"joined"},"next":"pop"},
            "pop":  {"id":"pop","type":"ACTION_ARRAY","params":{"action":"pop","var":"arr","output_var":"popped"},"next":"get"},
            "get":  {"id":"get","type":"ACTION_ARRAY","params":{"action":"get","var":"arr","index":1,"output_var":"got"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true &&
                (v["len1"] as? Number)?.toInt() == 3 &&
                (v["len2"] as? Number)?.toInt() == 4 &&
                v["joined"] == "a-b-c-d" &&
                v["popped"] == "d" &&
                v["got"] == "b"
        },
    )

    private fun testParseDateTime() = SelfTestCase(
        name = "ACTION_PARSE_DATETIME·now+parse 偏移",
        description = "now 后解析加 1 天应得到明天日期",
        workflowJson = """
        {
          "start_node_id": "now",
          "nodes": {
            "now":   {"id":"now","type":"ACTION_PARSE_DATETIME","params":{"action":"now","format":"yyyy-MM-dd HH:mm:ss","output_var":"ts"},"next":"parse"},
            "parse": {"id":"parse","type":"ACTION_PARSE_DATETIME","params":{"action":"parse","input":"${'$'}{ts}","input_format":"yyyy-MM-dd HH:mm:ss","add_days":1,"format":"yyyy-MM-dd","output_var":"tomorrow"},"next":"done"},
            "done":  {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["datetime_success"] == true &&
                ((v["datetime_epoch"] as? Number)?.toLong() ?: 0L) > 0L &&
                !(v["tomorrow"] as? String).isNullOrBlank()
        },
    )

    // ── 工具/时间 ──

    private fun testGetTime() = SelfTestCase(
        name = "ACTION_GET_TIME·格式化时间",
        description = "应输出非空时间串且年份合理",
        workflowJson = """
        {
          "start_node_id": "t",
          "nodes": {
            "t":    {"id":"t","type":"ACTION_GET_TIME","params":{"format":"yyyy-MM-dd HH:mm:ss","output_var":"my_time"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["time_success"] == true &&
                !(v["my_time"] as? String).isNullOrBlank() &&
                (v["current_year"] as? Number)?.toInt() ?: 0 >= 2025
        },
    )

    private fun testRandom() = SelfTestCase(
        name = "ACTION_RANDOM·范围内整数",
        description = "min=1 max=10 → 结果应在 1..10",
        workflowJson = """
        {
          "start_node_id": "r",
          "nodes": {
            "r":    {"id":"r","type":"ACTION_RANDOM","params":{"min":1,"max":10,"output_var":"rand1"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["random_success"] == true &&
                ((v["rand1"] as? Number)?.toInt() ?: -1) in 1..10
        },
    )

    private fun testDelay() = SelfTestCase(
        name = "ACTION_DELAY·短延时",
        description = "150ms 延时后应正常完成",
        workflowJson = """
        {
          "start_node_id": "d",
          "nodes": {
            "d":    {"id":"d","type":"ACTION_DELAY","params":{"millis":150},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true },
    )

    // ── 网络 ──

    private fun testHttp() = SelfTestCase(
        name = "ACTION_HTTP_REQUEST·GET",
        description = "GET https://www.baidu.com/ 应返回 2xx 且响应非空",
        timeoutMs = 45_000L,
        workflowJson = """
        {
          "start_node_id": "h",
          "nodes": {
            "h":    {"id":"h","type":"ACTION_HTTP_REQUEST","params":{"url":"https://www.baidu.com/","method":"GET","timeout":20000,"output_var":"resp"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["http_success"] == true &&
                !(v["http_response"] as? String).isNullOrBlank()
        },
    )

    private fun testNetworkStatus() = SelfTestCase(
        name = "ACTION_NETWORK_STATUS·all",
        description = "应返回网络连接状态与类型",
        workflowJson = """
        {
          "start_node_id": "n",
          "nodes": {
            "n":    {"id":"n","type":"ACTION_NETWORK_STATUS","params":{"action":"all"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["network_success"] == true &&
                v["network_connected"] is Boolean && v["network_type"] is String
        },
    )

    // ── UI ──

    private fun testToast() = SelfTestCase(
        name = "ACTION_TOAST·显示",
        description = "应正常完成（Toast 不崩溃）",
        workflowJson = """
        {
          "start_node_id": "toast",
          "nodes": {
            "toast": {"id":"toast","type":"ACTION_TOAST","params":{"message":"selftest"},"next":"done"},
            "done":  {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true },
    )

    private fun testVibrate() = SelfTestCase(
        name = "ACTION_VIBRATE·震动",
        description = "应正常完成（无震动器/权限时 handler 内部捕获）",
        workflowJson = """
        {
          "start_node_id": "v",
          "nodes": {
            "v":    {"id":"v","type":"ACTION_VIBRATE","params":{"duration":30,"amplitude":64},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true },
    )

    // ── 系统 ──

    private fun testShell() = SelfTestCase(
        name = "ACTION_SHELL·echo",
        description = "无 Shizuku 时应走 Runtime.exec 兜底并返回输出",
        workflowJson = """
        {
          "start_node_id": "s",
          "nodes": {
            "s":    {"id":"s","type":"ACTION_SHELL","params":{"command":"echo habitat-selftest-shell-ok"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["shell_success"] == true &&
                (v["shell_output"] as? String)?.contains("habitat-selftest-shell-ok") == true
        },
    )

    // ── 系统信息（零权限，阶段 C）──

    private fun testGetBattery() = SelfTestCase(
        name = "ACTION_GET_BATTERY·电量",
        description = "应返回 0-100 电量与充电状态",
        workflowJson = """
        {
          "start_node_id": "b",
          "nodes": {
            "b":    {"id":"b","type":"ACTION_GET_BATTERY","params":{"output_var":"bat"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["battery_success"] == true &&
                ((v["battery_level"] as? Number)?.toInt() ?: -1) in 0..100 &&
                v["battery_charging"] is Boolean
        },
    )

    private fun testGetDeviceInfo() = SelfTestCase(
        name = "ACTION_GET_DEVICE_INFO·设备信息",
        description = "应返回型号/Android 版本/屏幕尺寸",
        workflowJson = """
        {
          "start_node_id": "d",
          "nodes": {
            "d":    {"id":"d","type":"ACTION_GET_DEVICE_INFO","params":{},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["device_success"] == true &&
                !(v["device_model"] as? String).isNullOrBlank() &&
                ((v["device_sdk"] as? Number)?.toInt() ?: 0) >= 29 &&
                ((v["device_screen_width"] as? Number)?.toInt() ?: 0) > 0
        },
    )

    private fun testGetStorageInfo() = SelfTestCase(
        name = "ACTION_GET_STORAGE_INFO·存储空间",
        description = "应返回总/可用空间（字节）",
        workflowJson = """
        {
          "start_node_id": "s",
          "nodes": {
            "s":    {"id":"s","type":"ACTION_GET_STORAGE_INFO","params":{},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["storage_success"] == true &&
                ((v["storage_total"] as? Number)?.toLong() ?: 0L) > 0L &&
                ((v["storage_available"] as? Number)?.toLong() ?: -1L) >= 0L
        },
    )

    private fun testPlayMedia() = SelfTestCase(
        name = "ACTION_PLAY_MEDIA·播放",
        description = "播放本地文件（需真实媒体文件，真机验收时启用）",
        skipReason = "需要真实媒体文件（如 /sdcard/xxx.mp3），真机验收时启用",
        workflowJson = """
        {
          "start_node_id": "p",
          "nodes": {
            "p":    {"id":"p","type":"ACTION_PLAY_MEDIA","params":{"path":"${'$'}{filesDir}/selftest.mp3","timeout_ms":5000},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["play_success"] == true },
    )

    private fun testWaitUntilTime() = SelfTestCase(
        name = "ACTION_WAIT_UNTIL_TIME·等待时刻",
        description = "等待到 now+3s（timestamp 模式）应到达",
        timeoutMs = 20_000L,
        workflowJson = """
        {
          "start_node_id": "w",
          "nodes": {
            "w":    {"id":"w","type":"ACTION_WAIT_UNTIL_TIME","params":{"time":"${'$'}{selftest_target_ms}","mode":"timestamp","timeout_ms":15000,"output_var":"tr"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["time_reached"] == true },
    )

    private fun testGetSensor() = SelfTestCase(
        name = "ACTION_GET_SENSOR·光线传感器",
        description = "应读到一次光线强度（设备无光线传感器则 FAIL）",
        workflowJson = """
        {
          "start_node_id": "s",
          "nodes": {
            "s":    {"id":"s","type":"ACTION_GET_SENSOR","params":{"type":"light","output_var":"sv"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["sensor_success"] == true &&
                v["sensor_value"] is Number
        },
    )

    // ── 通话/系统（中风险，阶段 C）──

    private fun testGetCallState() = SelfTestCase(
        name = "ACTION_GET_CALL_STATE·电话状态",
        description = "应返回 0/1/2 状态（需授予 READ_PHONE_STATE）",
        workflowJson = """
        {
          "start_node_id": "c",
          "nodes": {
            "c":    {"id":"c","type":"ACTION_GET_CALL_STATE","params":{},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["call_success"] == true &&
                ((v["call_state"] as? Number)?.toInt() ?: -1) in 0..2
        },
    )

    private fun testGetSimInfo() = SelfTestCase(
        name = "ACTION_GET_SIM_INFO·SIM 卡信息",
        description = "应返回 SIM 数量与运营商（需授予 READ_PHONE_STATE）",
        workflowJson = """
        {
          "start_node_id": "s",
          "nodes": {
            "s":    {"id":"s","type":"ACTION_GET_SIM_INFO","params":{"output_var":"sim"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["sim_success"] == true &&
                ((v["sim_count"] as? Number)?.toInt() ?: -1) >= 0 &&
                !(v["sim_summary"] as? String).isNullOrBlank()
        },
    )

    private fun testDndMode() = SelfTestCase(
        name = "ACTION_DND_MODE·查询免打扰",
        description = "action=get 应返回当前状态（需通知使用权）",
        workflowJson = """
        {
          "start_node_id": "d",
          "nodes": {
            "d":    {"id":"d","type":"ACTION_DND_MODE","params":{"action":"get"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["dnd_success"] == true && v["dnd_state"] is String
        },
    )

    // ── 界面等待/导航（需无障碍）──

    private fun testWaitForElementDisappear() = SelfTestCase(
        name = "ACTION_WAIT_FOR_ELEMENT·消失判定",
        description = "不存在的元素应立刻判定为'已消失'",
        requiresAccessibility = true,
        timeoutMs = 15_000L,
        workflowJson = """
        {
          "start_node_id": "w",
          "nodes": {
            "w":    {"id":"w","type":"ACTION_WAIT_FOR_ELEMENT","params":{"selector":"habitat_absent_marker_xyz","mode":"text","wait_for":"disappear","timeout_ms":3000,"output_var":"ef"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["element_found"] == true },
    )

    private fun testWaitForElementAppearTimeout() = SelfTestCase(
        name = "ACTION_WAIT_FOR_ELEMENT·出现超时",
        description = "不存在的元素等待 appear 应在超时后返回 false",
        requiresAccessibility = true,
        timeoutMs = 15_000L,
        workflowJson = """
        {
          "start_node_id": "w",
          "nodes": {
            "w":    {"id":"w","type":"ACTION_WAIT_FOR_ELEMENT","params":{"selector":"habitat_absent_marker_xyz","mode":"text","wait_for":"appear","timeout_ms":2000,"output_var":"ef"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["element_found"] == false },
    )

    private fun testGlobalNav() = SelfTestCase(
        name = "ACTION_GLOBAL_NAV·全局导航",
        description = "导航动作有界面副作用（back 会退出宿主），真机验收时手工验证",
        skipReason = "导航动作有界面副作用（back 退出宿主/打开面板），真机验收时手工验证",
        workflowJson = """
        {
          "start_node_id": "n",
          "nodes": {
            "n":    {"id":"n","type":"ACTION_GLOBAL_NAV","params":{"action":"home"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["nav_success"] == true },
    )

    // ── 高风险（阶段 C，仅代码+用例，真机验收时启用）──

    private fun testRecordAudio() = SelfTestCase(
        name = "ACTION_RECORD_AUDIO·录音",
        description = "录制 1.5s 音频到 filesDir（需 RECORD_AUDIO 权限）",
        skipReason = "需 RECORD_AUDIO 权限 + 会录到周围声音，真机验收时手动启用",
        timeoutMs = 20_000L,
        workflowJson = """
        {
          "start_node_id": "r",
          "nodes": {
            "r":    {"id":"r","type":"ACTION_RECORD_AUDIO","params":{"duration":1500,"path":"${'$'}{filesDir}/selftest_record.m4a","output_var":"rp"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["record_success"] == true &&
                !(v["record_path"] as? String).isNullOrBlank()
        },
    )

    private fun testCapturePhoto() = SelfTestCase(
        name = "ACTION_CAPTURE_PHOTO·拍照",
        description = "拍摄一张照片保存 JPEG（需 CAMERA 权限）",
        skipReason = "需 CAMERA 权限 + 相机占用风险，真机验收时手动启用",
        timeoutMs = 25_000L,
        workflowJson = """
        {
          "start_node_id": "p",
          "nodes": {
            "p":    {"id":"p","type":"ACTION_CAPTURE_PHOTO","params":{"path":"${'$'}{filesDir}/selftest_photo.jpg","output_var":"pp"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["photo_success"] == true &&
                !(v["photo_path"] as? String).isNullOrBlank()
        },
    )

    private fun testGetLocation() = SelfTestCase(
        name = "ACTION_GET_LOCATION·位置",
        description = "读取最后已知位置（需定位权限 + 至少定位过一次）",
        skipReason = "需定位权限 + 位置隐私，真机验收时手动启用",
        workflowJson = """
        {
          "start_node_id": "l",
          "nodes": {
            "l":    {"id":"l","type":"ACTION_GET_LOCATION","params":{"output_var":"loc"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["location_success"] == true &&
                v["latitude"] is Number && v["longitude"] is Number
        },
    )

    private fun testReadNotifications() = SelfTestCase(
        name = "ACTION_READ_NOTIFICATIONS·通知列表",
        description = "读取当前通知（需在系统设置启用通知使用权）",
        skipReason = "需通知使用权（设置→通知使用权），真机验收时手动启用",
        workflowJson = """
        {
          "start_node_id": "n",
          "nodes": {
            "n":    {"id":"n","type":"ACTION_READ_NOTIFICATIONS","params":{"limit":10,"output_var":"notes"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v ->
            v["wf_completed"] == true && v["notification_success"] == true &&
                !(v["notifications_json"] as? String).isNullOrBlank()
        },
    )

    // ── 界面副作用节点（放最后）──

    private fun testSetAlarm() = SelfTestCase(
        name = "ACTION_SET_ALARM·设置闹钟",
        description = "应调起系统闹钟界面（会打开时钟 App，放最后执行）",
        workflowJson = """
        {
          "start_node_id": "a",
          "nodes": {
            "a":    {"id":"a","type":"ACTION_SET_ALARM","params":{"hour":7,"minute":30,"label":"selftest"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["alarm_success"] == true },
    )

    // ── 感知（需无障碍）──

    private fun testReadScreen() = SelfTestCase(
        name = "ACTION_READ_SCREEN·抓取文本",
        description = "无障碍连接时应输出全屏文本到 scr_text",
        requiresAccessibility = true,
        workflowJson = """
        {
          "start_node_id": "rs",
          "nodes": {
            "rs":   {"id":"rs","type":"ACTION_READ_SCREEN","params":{"keyword":"","output_var":"scr_text"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["scr_text"] is String },
    )

    private fun testGetAppInfo() = SelfTestCase(
        name = "ACTION_GET_APP_INFO·前台包名",
        description = "无障碍连接时应返回前台应用包名（非 unknown）",
        requiresAccessibility = true,
        workflowJson = """
        {
          "start_node_id": "ai",
          "nodes": {
            "ai":   {"id":"ai","type":"ACTION_GET_APP_INFO","params":{"output_var":"pkg"},"next":"done"},
            "done": {"id":"done","type":"ACTION_SET_VARIABLE","params":{"name":"wf_completed","value":true,"type":"bool"}}
          }
        }
        """.trimIndent(),
        verify = { v -> v["wf_completed"] == true && v["pkg"] is String && v["pkg"] != "unknown" },
    )
}
