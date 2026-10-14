package com.ailun.habitat.handlers

import android.provider.Telephony
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.api.IShellExecutor
import com.ailun.habitat.paramBool
import com.ailun.habitat.paramInt
import com.ailun.habitat.paramLongOrNull
import com.ailun.habitat.paramString
import kotlinx.coroutines.CancellationException
import java.util.regex.Pattern

/**
 * [ACTION_READ_SMS]：读取收件箱短信（按发件人/内容过滤、提取验证码）。
 * params：`filter_by`（latest/sender/content/both）、`sender`、`content`（均支持 ${var}）、
 * `max_scan`（默认 20）、`extract_code`（true 时只接受提取到验证码的短信；验证码须含数字）、
 * `within_minutes`（可选：只看最近 N 分钟内的短信）
 * 输出：sms_found、sms_sender、sms_content、sms_date、verification_code、sms_error
 */
class NodeReadSmsHandler(
    private val shellExecutor: IShellExecutor? = null,
) : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val app = context.appContext
        val p = node.params
        val filterBy = p?.get("filter_by")?.toString()?.trim()?.lowercase() ?: FILTER_LATEST
        val senderFilter = paramString(node, context, "sender")?.trim()?.takeIf { it.isNotEmpty() }
        val contentFilter = paramString(node, context, "content")?.trim()?.takeIf { it.isNotEmpty() }
        val maxScan = paramInt(node, context, "max_scan", 20).coerceIn(1, 500)
        val extractCode = paramBool(node, context, "extract_code", false)
        val sinceMs = paramLongOrNull(node, context, "within_minutes")?.takeIf { it > 0 }
            ?.let { System.currentTimeMillis() - it * 60_000L } ?: 0L
        context.putVariable("sms_found", false)

        // Strategy 1: ContentProvider (needs READ_SMS runtime permission)
        var cursor: android.database.Cursor? = null
        try {
            val sortOrder = "${Telephony.Sms.DATE} DESC"
            cursor = app.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                if (sinceMs > 0) "${Telephony.Sms.DATE} >= ?" else null,
                if (sinceMs > 0) arrayOf(sinceMs.toString()) else null,
                sortOrder,
            )

            if (cursor != null) {
                val addressIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                if (addressIdx >= 0 && bodyIdx >= 0) {
                    if (scanCursor(cursor, addressIdx, bodyIdx, dateIdx, maxScan, filterBy, senderFilter, contentFilter, extractCode, context)) {
                        return node.next
                    }
                }
            }
        } catch (e: SecurityException) {
            context.log("ReadSMS: SecurityException, falling back to shell")
        } catch (e: Exception) {
            context.log("ReadSMS: ${e.message}, falling back to shell")
        } finally {
            cursor?.close()
        }

        // Strategy 2: Shell via Shizuku (bypasses READ_SMS permission)
        val shell = shellExecutor
        if (shell != null) {
            context.log("ReadSMS: trying shell fallback...")
            val result = readSmsViaShell(shell, maxScan, sinceMs, filterBy, senderFilter, contentFilter, extractCode, context)
            if (result) return node.next
        }

        context.putVariable("sms_found", false)
        context.putVariable("sms_error", "Cannot access SMS — grant READ_SMS or enable Shizuku")
        return node.next
    }

    private fun scanCursor(
        cursor: android.database.Cursor,
        addressIdx: Int,
        bodyIdx: Int,
        dateIdx: Int,
        maxScan: Int,
        filterBy: String,
        senderFilter: String?,
        contentFilter: String?,
        extractCode: Boolean,
        context: WorkflowContext,
    ): Boolean {
        var scanned = 0
        while (cursor.moveToNext() && scanned < maxScan) {
            scanned++
            val sender = cursor.getString(addressIdx) ?: ""
            val body = cursor.getString(bodyIdx) ?: ""
            val date = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L

            val code = if (extractCode) extractVerificationCode(body) else ""
            val isMatch = matches(sender, body, code, filterBy, senderFilter, contentFilter, extractCode)
            if (isMatch && (!extractCode || code.isNotEmpty())) {
                setResult(context, sender, body, code, date)
                return true
            }
        }
        return false
    }

    private suspend fun readSmsViaShell(
        shell: IShellExecutor,
        maxScan: Int,
        sinceMs: Long,
        filterBy: String,
        senderFilter: String?,
        contentFilter: String?,
        extractCode: Boolean,
        context: WorkflowContext,
    ): Boolean {
        try {
            val output = shell.exec(
                "content query --uri content://sms/inbox --projection address:body:date --sort \"date DESC\"",
                asRoot = false,
            )
            if (output.startsWith("Error") || output.isBlank()) {
                context.log("ReadSMS shell: $output")
                return false
            }

            var scanned = 0
            for (line in output.lines()) {
                if (scanned >= maxScan) break
                val trimmed = line.trim()
                if (!trimmed.startsWith("Row:")) continue

                scanned++
                val sender = extractField(trimmed, "address")
                val body = extractField(trimmed, "body")
                if (sender.isEmpty() && body.isEmpty()) continue
                val date = extractField(trimmed, "date").toLongOrNull() ?: 0L
                // 按日期倒序：早于时间窗口即可停止
                if (sinceMs > 0 && date in 1 until sinceMs) break

                val code = if (extractCode) extractVerificationCode(body) else ""
                val isMatch = matches(sender, body, code, filterBy, senderFilter, contentFilter, extractCode)
                if (isMatch && (!extractCode || code.isNotEmpty())) {
                    setResult(context, sender, body, code, date)
                    context.log("ReadSMS shell: found code=$code")
                    return true
                }
            }
            context.log("ReadSMS shell: scanned $scanned messages, no match")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            context.log("ReadSMS shell error: ${e.message}")
        }
        return false
    }

    private fun extractField(line: String, fieldName: String): String {
        // Parses "Row: N address=106xxxxx, body=xxx, date=xxx"
        val pattern = Pattern.compile("""$fieldName=([^,]*(?:,[^,]+)*?)(?:, \w+=|$)""")
        val m = pattern.matcher(line)
        return if (m.find()) m.group(1)?.trim() ?: "" else ""
    }

    private fun matches(
        sender: String, body: String, code: String,
        filterBy: String, senderFilter: String?, contentFilter: String?, extractCode: Boolean,
    ): Boolean {
        val senderMatches = senderFilter.isNullOrEmpty() || sender.contains(senderFilter, ignoreCase = true)
        val contentMatches = if (extractCode) {
            code.isNotEmpty()
        } else {
            contentFilter.isNullOrEmpty() || body.contains(contentFilter, ignoreCase = true)
        }
        return when (filterBy) {
            FILTER_LATEST -> true
            FILTER_SENDER -> senderMatches
            FILTER_CONTENT -> contentMatches
            FILTER_BOTH -> senderMatches && contentMatches
            else -> false
        }
    }

    private fun setResult(context: WorkflowContext, sender: String, body: String, code: String, date: Long) {
        context.putVariable("sms_found", true)
        context.putVariable("sms_date", date)
        context.putVariable("sms_sender", sender)
        context.putVariable("sms_content", body)
        context.putVariable("verification_code", code)
    }

    private fun extractVerificationCode(text: String): String {
        // 1) 纯数字 4-8 位；2) 字母数字混合 6-12 位且至少含一个数字（避免匹配普通英文单词）
        val digits = Pattern.compile("""(?<!\d)\d{4,8}(?!\d)""").matcher(text)
        if (digits.find()) return digits.group()
        val mixed = Pattern.compile("""(?<![A-Za-z0-9])(?=[A-Za-z]*\d)[A-Za-z0-9]{6,12}(?![A-Za-z0-9])""").matcher(text)
        if (mixed.find()) return mixed.group()
        return ""
    }

    companion object {
        const val FILTER_LATEST = "latest"
        const val FILTER_SENDER = "sender"
        const val FILTER_CONTENT = "content"
        const val FILTER_BOTH = "both"
    }
}
