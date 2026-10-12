package com.ailun.habitat.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 意图解析：句式覆盖 + 槽位必须是目标原文子串（号码除外）。 */
class IntentParserTest {

    private fun assertSlotsFromGoal(goal: String) {
        val p = IntentParser.parse(goal)
        p.contact?.let { assertTrue("contact「$it」不在目标里", goal.contains(it)) }
        p.text?.let { assertTrue("text「$it」不在目标里", goal.contains(it)) }
        IntentParser.inputTexts(goal).forEach { assertTrue("输入「$it」不在目标里", goal.contains(it)) }
    }

    @Test
    fun callToCarrierIsACallNotAnApp() {
        val p = IntentParser.parse("打电话给中国移动")
        assertEquals(IntentKind.CALL, p.kind)
        assertEquals("中国移动", p.contact)
        assertEquals("妈妈", IntentParser.parse("给妈妈打个电话").contact)
        assertEquals("10086", IntentParser.parse("拨打10086").contact)
        assertEquals("张三", IntentParser.parse("打给张三吧").contact)
        assertEquals("中国移动客服", IntentParser.parse("帮我拨打电话给中国移动客服").contact)
    }

    @Test
    fun notEveryPhoneWordIsACall() {
        assertEquals(IntentKind.GENERIC, IntentParser.parse("打开中国移动").kind)
        assertEquals(IntentKind.GENERIC, IntentParser.parse("用微信给张三打语音电话").kind)
        assertEquals(IntentKind.GENERIC, IntentParser.parse("打开设置里的呼叫转移设置").kind)
        assertEquals(IntentKind.GENERIC, IntentParser.parse("查看通话记录").kind)
    }

    @Test
    fun sendMessageSlots() {
        val qq = IntentParser.parse("打开qq给我的电脑发消息说你好")
        assertEquals(IntentKind.SEND_MESSAGE, qq.kind)
        assertEquals("我的电脑", qq.contact)
        assertEquals("你好", qq.text)
        assertEquals("qq", qq.channel)

        val wx = IntentParser.parse("用微信给张三发消息，说今天晚点到，然后返回桌面")
        assertEquals("张三", wx.contact)
        assertEquals("今天晚点到", wx.text)
        assertEquals("wechat", wx.channel)

        val quoted = IntentParser.parse("在QQ上把「测试123」发给我的电脑")
        assertEquals("我的电脑", quoted.contact)
        assertEquals("测试123", quoted.text)

        val colon = IntentParser.parse("发微信给李四：明天见")
        assertEquals("李四", colon.contact)
        assertEquals("明天见", colon.text)
    }

    @Test
    fun messageWithoutContentHasNoText() {
        val p = IntentParser.parse("给张三发消息")
        assertEquals(IntentKind.SEND_MESSAGE, p.kind)
        assertNull(p.text)
        assertNull(p.channel)
        // 「把这张图片发给张三」没有引号：不能把「这张图片」当成要输入的文字
        assertTrue(IntentParser.parse("把这张图片发给张三").text == null)
    }

    @Test
    fun inputTextsDoNotNeedASpace() {
        assertEquals(listOf("电池"), IntentParser.inputTexts("打开设置，在搜索框输入电池并搜索"))
        assertEquals(listOf("电池"), IntentParser.inputTexts("打开设置，在搜索框输入「电池」并搜索"))
        assertEquals(listOf("天气预报"), IntentParser.inputTexts("打开浏览器搜一下天气预报"))
        assertEquals(listOf("你好"), IntentParser.inputTexts("在搜索框输入「你好」然后发送"))
        // 发消息：正文在前，联系人在后（搜会话时要输入联系人）
        assertEquals(listOf("你好", "我的电脑"), IntentParser.inputTexts("打开qq给我的电脑发消息说你好"))
    }

    @Test
    fun slotsAlwaysComeFromTheGoal() {
        listOf(
            "打开qq给我的电脑发消息说你好",
            "用微信给张三发消息，说今天晚点到，然后返回桌面",
            "打电话给中国移动",
            "在设置里搜索蓝牙",
            "给老王发条短信：我到了。",
            "把「早上好」发给妈妈",
        ).forEach(::assertSlotsFromGoal)
    }

    @Test
    fun serviceNumbersAndContactPriority() {
        assertEquals("10086", ServiceNumbers.lookup("中国移动"))
        assertEquals("10086", ServiceNumbers.lookup("中国移动客服"))
        assertEquals("95588", ServiceNumbers.lookup("工商银行"))
        assertNull(ServiceNumbers.lookup("妈妈"))
        assertEquals("13800138000", ServiceNumbers.asNumber("138 0013 8000"))
        assertNull(ServiceNumbers.asNumber("张三"))
        // 通讯录精确匹配优先于客服表：「电信张经理」是人，不是 10000
        val exact = mapOf("电信张经理" to "13900000000")
        assertEquals("13900000000", ServiceNumbers.resolve("电信张经理", { exact[it] }, { null })?.number)
        assertEquals("10000", ServiceNumbers.resolve("中国电信", { null }, { null })?.number)
        assertNull(ServiceNumbers.resolve("不认识的人", { null }, { null }))
    }
}
