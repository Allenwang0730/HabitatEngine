package com.ailun.habitat.handlers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_CALL_DIRECT]：用 `ACTION_CALL` **直接拨出**（无需无障碍点拨打键）。
 *
 * params：
 * - `phone_number`（必填）：电话号码，支持 ${var} 插值
 * - `sim`（可选）："auto" 自动（默认）/ "1" 卡1 / "2" 卡2，用于双卡指定 SIM
 *
 * 输出：`call_success`（Boolean）
 * 注意：需要 `CALL_PHONE` 权限；免无障碍流程推荐，荣耀/华为等均可正常拨出。
 */
class NodeCallDirectHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawNumber = node.params?.get("phone_number")?.toString()?.trim().orEmpty()
        if (rawNumber.isEmpty()) {
            Log.e(TAG, "CallDirect failed: 'phone_number' parameter is empty")
            context.putVariable("call_success", false)
            return node.next
        }

        if (context.appContext.checkSelfPermission(android.Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "CallDirect failed: CALL_PHONE permission not granted")
            context.putVariable("call_success", false)
            return node.next
        }

        val phoneNumber = context.interpolate(rawNumber).trim()
        // 只允许拨号字符，防止变量注入 USSD/MMI 等特殊串以外的内容
        if (!PHONE_REGEX.matches(phoneNumber)) {
            Log.e(TAG, "CallDirect failed: invalid phone number")
            context.putVariable("call_success", false)
            context.putVariable("call_error", "号码格式非法")
            return node.next
        }
        val simPref = node.params?.get("sim")?.toString()?.trim()?.ifEmpty { "auto" } ?: "auto"

        // 用 TelecomManager.placeCall（系统底层拨号 API）：后台运行也能拨，不依赖前台 Activity，
        // 避免 startActivity(ACTION_CALL) 在后台启动受限/被当 USSD 报"MMI 码无效"的问题。
        val success = runCatching {
            val tm = context.appContext.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val extras = android.os.Bundle()
            resolvePhoneAccountHandle(context.appContext, simPref)?.let {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
            }
            // placeCall 无返回值；未抛异常即视为已提交拨号
            tm.placeCall(Uri.fromParts("tel", phoneNumber, null), extras)
            true
        }.onSuccess { Log.i(TAG, "CallDirect placed via placeCall: $phoneNumber (sim=$simPref)") }
            .onFailure { Log.e(TAG, "CallDirect placeCall failed: ${it.message}", it) }
            .getOrElse { false }

        context.putVariable("call_success", success)
        return node.next
    }

    /** 双卡：按 "auto"/"1"/"2" 解析 PhoneAccountHandle */
    private fun resolvePhoneAccountHandle(context: Context, simPreference: String): PhoneAccountHandle? {
        if (simPreference == "auto") return null
        val idx = simPreference.toIntOrNull()?.minus(1) ?: return null
        return runCatching {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.callCapablePhoneAccounts.getOrNull(idx)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "HabitatCallDirect"
        private val PHONE_REGEX = Regex("^[0-9+*#,; ()\\-]{1,40}$")
    }
}
