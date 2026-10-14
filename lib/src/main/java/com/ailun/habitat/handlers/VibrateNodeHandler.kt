package com.ailun.habitat.handlers

import android.content.Context
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VibrateNodeHandler : INodeHandler {
    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val durationMs = parseLong(node.params?.get("duration"))
            ?: parseLong(node.params?.get("ms"))
            ?: 200L
        val amplitude = ((node.params?.get("amplitude") as? Number)?.toInt()
            ?: node.params?.get("amplitude")?.toString()?.toIntOrNull()
            ?: 128).coerceIn(1, 255)
        val duration = durationMs.coerceIn(1L, 10_000L)

        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
                context.log("Vibrate dur=${duration}ms amp=$amplitude")
            } else {
                context.log("Vibrate SKIP: no vibrator available")
            }
        } catch (_: Exception) {
            context.log("Vibrate FAILED")
        }
        return node.next
    }

    private fun parseLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}
