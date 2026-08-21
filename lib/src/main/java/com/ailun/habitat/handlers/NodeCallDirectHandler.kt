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
            context.variables["call_success"] = false
            return node.next
        }

        if (context.appContext.checkSelfPermission(android.Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "CallDirect failed: CALL_PHONE permission not granted")
            context.variables["call_success"] = false
            return node.next
        }

        val phoneNumber = context.interpolate(rawNumber)
        val simPref = node.params?.get("sim")?.toString()?.trim()?.ifEmpty { "auto" } ?: "auto"

        val uri = Uri.parse("tel:$phoneNumber")
        val intent = Intent(Intent.ACTION_CALL, uri).apply {
            // 从 application context 启动 Activity 必须带 NEW_TASK
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        resolvePhoneAccountHandle(context.appContext, simPref)?.let {
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
        }

        val success = runCatching { context.appContext.startActivity(intent) }
            .onSuccess { Log.i(TAG, "CallDirect placed: $phoneNumber (sim=$simPref)") }
            .onFailure { Log.e(TAG, "CallDirect failed: ${it.message}", it) }
            .isSuccess

        context.variables["call_success"] = success
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
    }
}
