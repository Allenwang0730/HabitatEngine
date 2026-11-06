package com.ailun.habitat.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 软惩罚：失败项留在候选里，按分数降低被选中的概率；不受罚时与旧行为一致。 */
class PenaltiesTest {

    private fun el(i: String, text: String, y: Int, viewId: String = "") = ScreenElement(
        i, text, 540, y, clickable = true, editable = false, focused = false, viewId = viewId,
        bounds = ElementBounds(0, y - 40, 1080, y + 40),
    )

    private val obs = ScreenObservation(
        packageName = "com.android.settings", screenWidth = 1080, screenHeight = 2400,
        elements = listOf(el("1", "Bluetooth", 600, "id/title_bt"), el("2", "Wi-Fi", 800, "id/title_wifi")),
        visibleText = "Bluetooth\nWi-Fi", source = "a11y",
    )

    @Test
    fun noChangeErrorAndDeadEndAccumulate() {
        val catalog = Catalogs.build("Turn bluetooth on", obs, emptyList())
        val bt = catalog.actions.first { it.operation == "TAP" && it.target == "1" }
        val sig = LoopPolicy.signatureOf(bt, obs)
        val history = listOf(
            StepNote(bt.id, "TAP", bt.label, changed = false, signature = sig),
            StepNote(bt.id, "TAP", bt.label, changed = false, signature = sig, error = "节点返回失败"),
        )
        val p = Penalties.forCatalog(catalog, history, obs)
        assertEquals(Penalties.NO_CHANGE + Penalties.ERROR, p.getValue(bt.id), 1e-9)
        val wifi = catalog.actions.first { it.operation == "TAP" && it.target == "2" }
        assertFalse(wifi.id in p)
        // 完成/放弃/等待永远不罚
        assertTrue(catalog.actions.filter { it.operation in setOf("DONE", "BLOCKED", "WAIT") }.none { it.id in p })
    }

    @Test
    fun softModeKeepsTheCandidateThatHardModeDeletes() {
        // 点进子页、几步内又退回：旧规则把这个入口判死路并删掉
        val catalog = Catalogs.build("Turn bluetooth off", obs, emptyList())
        val bt = catalog.actions.first { it.operation == "TAP" && it.target == "1" }
        val history = listOf(
            StepNote(bt.id, "TAP", bt.label, changed = true, signature = LoopPolicy.signatureOf(bt, obs)),
            StepNote("back", "BACK", "返回", changed = true, signature = "BACK||com.android.settings"),
        )
        val hard = catalog.without(LoopPolicy.deadEnds(history)) { LoopPolicy.signatureOf(it, obs) }
        assertFalse("1" in hard.tapTargets)
        val soft = catalog.copy(penalties = Penalties.forCatalog(catalog, history, obs))
        assertTrue("1" in soft.tapTargets)
        assertEquals(Penalties.DEAD_END, soft.penaltyOf("TAP", "1"), 1e-9)
        // 操作级惩罚取最轻的目标：Wi-Fi 没受罚，TAP 本身不被压低
        assertEquals(0.0, soft.operationPenalty("TAP"), 1e-9)
    }

    @Test
    fun avoidListFromReflectionPenalizesMatchingLabels() {
        val catalog = Catalogs.build("Turn bluetooth on", obs, emptyList())
        val p = Penalties.forCatalog(catalog, emptyList(), obs, avoid = listOf("点击「Bluetooth」"))
        val bt = catalog.actions.first { it.operation == "TAP" && it.target == "1" }
        assertEquals(Penalties.AVOIDED, p.getValue(bt.id), 1e-9)
        // 太短的 avoid 不匹配（「确定」这种词会误伤一大片）
        assertTrue(Penalties.forCatalog(catalog, emptyList(), obs, avoid = listOf("B")).isEmpty())
    }

    @Test
    fun rerankFlipsOnlyWhenPenaltyOutweighsTheMargin() {
        val probs = mapOf("1" to 0.55, "2" to 0.45)
        val none = Penalties.rerank(probs, probs.keys) { 0.0 }!!
        assertEquals("1", none.choice)
        assertFalse(none.flipped)
        // 1 号受罚 2 分：0.55·e^-1.6 ≈ 0.11 < 0.45 → 改选 2
        val flipped = Penalties.rerank(probs, probs.keys) { if (it == "1") 2.0 else 0.0 }!!
        assertEquals("2", flipped.choice)
        assertTrue(flipped.flipped)
        // 差距大时小惩罚不翻盘：0.95·e^-0.8 ≈ 0.43 > 0.05
        val kept = Penalties.rerank(mapOf("1" to 0.95, "2" to 0.05), setOf("1", "2")) { if (it == "1") 1.0 else 0.0 }!!
        assertEquals("1", kept.choice)
        // 不在候选里的选项忽略
        assertEquals("2", Penalties.rerank(mapOf("9" to 0.9, "2" to 0.1), setOf("1", "2")) { 0.0 }!!.choice)
    }

    @Test
    fun deepSeekChoicesSkipNonCandidatesAndApplyPenalty() {
        val catalog = Catalogs.build("Turn bluetooth on", obs, emptyList())
        val bt = catalog.actions.first { it.operation == "TAP" && it.target == "1" }
        val soft = catalog.copy(penalties = mapOf(bt.id to 3.0))
        // 首选 TAP/9 不在候选里：退到下一个；TAP/1 受罚重，选 TAP/2
        val raw = """{"choices":[{"operation":"TAP","target":"9","p":0.6},{"operation":"TAP","target":"1","p":0.3},{"operation":"TAP","target":"2","p":0.1}]}"""
        val d = parseModelChoices(raw, soft)
        assertEquals("action", d.status)
        assertEquals("2", d.target)
        assertTrue(d.detail.contains("惩罚后改选"))
        assertEquals(1.0, d.confidence, 1e-9)
        // 旧的单选格式照常解析
        val single = parseModelChoices("""{"operation":"TAP","target":"2"}""", soft)
        assertEquals("2", single.target)
        // 一个备选都不合法 → 和旧格式一样报错
        assertEquals("error", parseModelChoices("""{"choices":[{"operation":"TAP","target":"9"}]}""", soft).status)
    }

    @Test
    fun copyTextIsGatedAndLimitedToGoalText() {
        val goal = "Copy the following text to the clipboard: Order No: B654321"
        assertTrue("copy_text" in DeviceTools.available(goal).map { it.id })
        val inv = DeviceTools.resolve("copy_text", mapOf("text" to "Order No: B654321"), goal, emptyList())
        assertNotNull(inv)
        assertEquals("ACTION_CLIPBOARD", inv!!.node)
        assertEquals("set", inv.params["action"])
        // 目标里没有的文字不能写进剪贴板
        assertEquals(null, DeviceTools.resolve("copy_text", mapOf("text" to "something else"), goal, emptyList()))
        // 「复制文件」不是剪贴板
        assertFalse("copy_text" in DeviceTools.available("Copy the file note.md to folder Archive").map { it.id })
    }

    @Test
    fun englishGoalsUnlockTheMatchingDeviceTools() {
        // 只认中文时，英文「Turn brightness to the min value.」拿不到亮度工具，只能在界面上拖（AndroidWorld 0/4）
        val ids = { g: String -> DeviceTools.available(g).map { it.id } }
        assertTrue("set_brightness" in ids("Turn brightness to the min value."))
        assertTrue("set_volume" in ids("Mute the ring volume"))
        assertTrue("set_dnd" in ids("Turn on Do Not Disturb"))
        assertFalse("set_brightness" in ids("Open the display settings"))
        val inv = DeviceTools.resolve("set_brightness", mapOf("level" to "min"), "Turn brightness to the min value.", emptyList())!!
        assertEquals(1, inv.params["value"])
    }

    @Test
    fun focusedInputCanBeLongPressed() {
        val input = ScreenElement("3", "Order No: B654321", 540, 200, clickable = true, editable = true, focused = true,
            bounds = ElementBounds(40, 160, 1040, 240))
        val o = obs.copy(elements = obs.elements + input, focusedIndex = "3")
        val catalog = Catalogs.build("Copy the following text to the clipboard: Order No: B654321", o, emptyList())
        assertFalse("3" in catalog.tapTargets)
        assertTrue("3" in catalog.longPressTargets)
    }

    @Test
    fun pickingTextInsideACardTapsTheCard() {
        // 卡片整块可点（无文字，有 viewId），标题文字是另一个不可点的元素，坐标落在卡片里
        val card = ScreenElement("1", "", 540, 700, clickable = true, editable = false, focused = false,
            viewId = "com.flauschcode.broccoli:id/card_view", bounds = ElementBounds(40, 500, 1040, 900))
        val title = ScreenElement("2", "Eggplant Parmesan", 400, 600, clickable = false, editable = false, focused = false,
            bounds = ElementBounds(80, 560, 720, 640))
        val o = obs.copy(packageName = "com.flauschcode.broccoli", elements = listOf(card, title), visibleText = "Eggplant Parmesan")
        val catalog = Catalogs.build("Delete the recipe Eggplant Parmesan", o, emptyList())
        assertTrue("1" in catalog.tapTargets)
        assertEquals("1", catalog.containerOf["2"])
        val d = decisionFromChoice(catalog, "TAP", "2", 1.0, "DeepSeek 选择 TAP")
        assertEquals("action", d.status)
        assertEquals("1", d.target)
        assertTrue(d.detail.contains("改点包住它的 1"))
        // 不在任何候选里的选择照旧报错
        assertEquals("error", decisionFromChoice(catalog, "TAP", "9", 1.0, "").status)
    }

    @Test
    fun pageSizedContainerIsNotAnIconButton() {
        val list = ScreenElement("1", "", 540, 1200, clickable = true, editable = false, focused = false,
            viewId = "com.flauschcode.broccoli:id/recycler_view", bounds = ElementBounds(0, 300, 1080, 2300))
        assertEquals(null, Catalogs.iconLabel(list, 1080, 2400))
        val o = obs.copy(elements = listOf(list))
        assertFalse("1" in Catalogs.build("Delete a recipe", o, emptyList()).tapTargets)
    }
}
