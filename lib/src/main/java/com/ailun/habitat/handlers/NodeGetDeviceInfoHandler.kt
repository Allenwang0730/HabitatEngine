package com.ailun.habitat.handlers

import android.os.Build
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_GET_DEVICE_INFO]：读取设备基本信息（零权限）。
 * 输出：device_model、device_manufacturer、device_brand、device_android_version、
 *       device_sdk、device_screen_width、device_screen_height、device_density、device_success
 */
class NodeGetDeviceInfoHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        try {
            val dm = context.appContext.resources.displayMetrics
            context.putVariable("device_model", Build.MODEL)
            context.putVariable("device_manufacturer", Build.MANUFACTURER)
            context.putVariable("device_brand", Build.BRAND)
            context.putVariable("device_android_version", Build.VERSION.RELEASE)
            context.putVariable("device_sdk", Build.VERSION.SDK_INT)
            context.putVariable("device_screen_width", dm.widthPixels)
            context.putVariable("device_screen_height", dm.heightPixels)
            context.putVariable("device_density", dm.density)
            context.putVariable("device_success", true)
            context.log("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
                "${dm.widthPixels}x${dm.heightPixels}")
        } catch (e: Exception) {
            context.putVariable("device_success", false)
            context.putVariable("device_error", e.message ?: "Unknown error")
            context.log("DeviceInfo FAILED: ${e.message}")
        }
        return node.next
    }
}
