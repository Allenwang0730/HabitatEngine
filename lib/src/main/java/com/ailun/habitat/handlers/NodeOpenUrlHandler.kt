package com.ailun.habitat.handlers

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import com.ailun.habitat.paramString
import java.net.URI

/**
 * [ACTION_OPEN_URL]：用系统默认浏览器（或认领该链接的 App）打开一个网址。
 *
 * params：`url`（必填，只接受 http / https；不带协议时按 https 补全）、`package`（可选，指定用哪个 App 打开，如 com.android.chrome）
 *
 * 输出：`open_url_success`、`open_url`（实际打开的规范化网址）、`open_url_error`。
 * 不允许 javascript: / file: / content: / intent: 等协议，也不允许网址里带用户名密码。
 */
class NodeOpenUrlHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        context.putVariable("open_url_success", false)
        context.putVariable("open_url", null)
        context.putVariable("open_url_error", null)
        val raw = paramString(node, context, "url")?.trim().orEmpty()
        val url = normalize(raw) ?: return fail(context, node, "网址不合法或协议不允许（只接受 http / https）：${raw.take(200)}")
        val pkg = paramString(node, context, "package")?.trim()?.takeIf { it.isNotEmpty() }
        if (pkg != null && !com.ailun.habitat.ShellQuote.isPackageName(pkg)) return fail(context, node, "包名不合法：$pkg")

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) intent.setPackage(pkg)
        return try {
            context.appContext.startActivity(intent)
            context.putVariable("open_url_success", true)
            context.putVariable("open_url", url)
            context.log("打开网址：$url")
            node.next
        } catch (e: ActivityNotFoundException) {
            fail(context, node, if (pkg != null) "$pkg 不能打开这个网址（没安装？）" else "没有能打开网址的浏览器")
        } catch (e: SecurityException) {
            fail(context, node, "系统拒绝打开：${e.message}")
        }
    }

    private fun fail(context: WorkflowContext, node: WorkflowNode, reason: String): String? {
        context.putVariable("open_url_error", reason)
        context.log("打开网址失败：$reason")
        return node.next
    }

    companion object {
        private const val MAX_LENGTH = 2048

        /** 规范化并校验网址；不合法返回 null。纯逻辑，单测覆盖。 */
        fun normalize(input: String): String? {
            val text = input.trim()
            if (text.isEmpty() || text.length > MAX_LENGTH || text.any { it.isWhitespace() || it.code < 0x20 }) return null
            val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(text)?.groupValues?.get(1)?.lowercase()
            val withScheme = when {
                scheme == null -> "https://$text"
                scheme == "http" || scheme == "https" -> text
                // "example.com:8080/x" 被当成了协议 example.com；只有像域名的才补 https
                scheme.contains('.') -> "https://$text"
                else -> return null
            }
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            val s = uri.scheme?.lowercase()
            if (s != "http" && s != "https") return null
            val host = uri.host ?: return null
            if (host.isEmpty() || uri.rawUserInfo != null) return null
            return withScheme.replaceFirst(Regex("^[A-Za-z]+"), s)
        }
    }
}
