package com.ailun.habitat.app.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/** 解析出来的拨号/发信对象。号码只在本机用，不进任何提示词。 */
data class Callee(val name: String, val number: String)

/**
 * 本次任务里代码查到的实体。设备工具只能用这里的值——规划器/执行器给不出号码。
 * @param allowDirectCall 本机发起且有 CALL_PHONE 权限：直接拨出；否则只打开拨号盘填好号码。
 */
data class ToolScope(
    val callee: Callee? = null,
    val allowDirectCall: Boolean = false,
)

/** 常用客服号码。名字按"包含"匹配，长的别名在前（「中国移动客服」先于「移动」）。 */
object ServiceNumbers {
    private val TABLE: List<Pair<List<String>, String>> = listOf(
        listOf("中国移动", "移动客服", "移动营业厅") to "10086",
        listOf("中国联通", "联通客服", "联通") to "10010",
        listOf("中国电信", "电信客服", "电信") to "10000",
        listOf("中国广电", "广电") to "10099",
        listOf("工商银行", "工行") to "95588",
        listOf("建设银行", "建行") to "95533",
        listOf("农业银行", "农行") to "95599",
        listOf("中国银行", "中行") to "95566",
        listOf("交通银行", "交行") to "95559",
        listOf("招商银行", "招行") to "95555",
        listOf("邮政储蓄", "邮储银行") to "95580",
        listOf("顺丰") to "95338",
        listOf("中国邮政", "邮政快递", "ems") to "11183",
        listOf("铁路客服", "12306") to "12306",
        listOf("支付宝") to "95188",
        listOf("国家电网", "供电局", "电力客服") to "95598",
        listOf("消费者投诉", "消费者协会") to "12315",
        listOf("政务服务", "市民热线") to "12345",
        listOf("移动") to "10086",
    )

    /** 紧急号码：只填进拨号盘，不自动拨出（误拨后果太重）。 */
    val EMERGENCY = setOf("110", "112", "119", "120", "122", "911", "999")

    private val DIAL_CHARS = Regex("^[+]?[0-9][0-9 \\-]{1,19}$")

    fun lookup(name: String): String? {
        val n = name.lowercase()
        return TABLE.firstOrNull { (aliases, _) -> aliases.any { n.contains(it) } }?.second
    }

    /** 名字本身就是号码（「打给10086」「拨打138 0013 8000」）。 */
    fun asNumber(name: String): String? =
        name.trim().takeIf { DIAL_CHARS.matches(it) }?.filterNot { it == ' ' || it == '-' }

    /**
     * 名字 → 号码：直接写的号码 → 通讯录精确匹配 → 客服表 → 通讯录模糊匹配。
     * 精确匹配排在客服表前：通讯录里的「电信张经理」不能被当成 10000。
     */
    fun resolve(name: String, exact: (String) -> String?, fuzzy: (String) -> String?): Callee? {
        val number = asNumber(name) ?: exact(name) ?: lookup(name) ?: fuzzy(name) ?: return null
        return Callee(name, number)
    }
}

/** 通讯录按名字查号码。没有 READ_CONTACTS 权限时返回 null，不抛异常。 */
object ContactResolver {
    fun readable(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun exact(context: Context, name: String): String? {
        if (!readable(context) || name.isBlank()) return null
        return query(context, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?", arrayOf(name)).firstOrNull()
    }

    /** 模糊命中多个人时不猜。 */
    fun fuzzy(context: Context, name: String): String? {
        if (!readable(context) || name.isBlank()) return null
        return query(context, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'", arrayOf("%${escapeLike(name)}%"))
            .distinct().singleOrNull()
    }

    private fun query(context: Context, selection: String, args: Array<String>): List<String> = runCatching {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
            selection, args, null,
        )?.use { c ->
            val numbers = mutableListOf<String>()
            val seenContacts = mutableSetOf<Long>()
            while (c.moveToNext()) {
                val contactId = c.getLong(1)
                // 同一个人多个号码只取第一个；不同的人才算"多个"
                if (seenContacts.add(contactId)) c.getString(0)?.filterNot { it == ' ' || it == '-' }?.let(numbers::add)
            }
            numbers
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
