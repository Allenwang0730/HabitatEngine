package com.ailun.habitat.handlers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ACTION_GET_SENSOR]：读取一次传感器数值（零权限）。
 * params：
 * - `type`（可选）："light"（默认）/ "proximity" / "accel"（accelerometer）
 * - `output_var`（可选）：输出变量名，默认 "sensor_value"
 * 输出：sensor_value(Float)、sensor_type、sensor_success
 *
 * 实现要点：Handler 跑在无 Looper 的 Dispatchers.Default 上，
 * 用自带 HandlerThread 承载 registerListener 回调（符合线程约束），
 * 读到首个读数即 unregister 并 quit。
 */
class NodeGetSensorHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val typeStr = node.params?.get("type")?.toString()?.trim()?.lowercase() ?: "light"
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "sensor_value"

        val sm = context.appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sm == null) {
            context.variables["sensor_success"] = false
            context.variables["sensor_error"] = "SensorManager not available"
            return node.next
        }

        val sensorType = when (typeStr) {
            "light" -> Sensor.TYPE_LIGHT
            "proximity" -> Sensor.TYPE_PROXIMITY
            "accel", "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            else -> -1
        }
        val sensor = if (sensorType >= 0) sm.getDefaultSensor(sensorType) else null
        if (sensor == null) {
            context.variables["sensor_success"] = false
            context.variables["sensor_error"] = "No ${typeStr} sensor on this device"
            context.log("GetSensor FAILED: no $typeStr sensor")
            return node.next
        }

        val thread = HandlerThread("HabitatSensor").apply { start() }
        val handler = Handler(thread.looper)
        val deferred = CompletableDeferred<Float>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) deferred.complete(event.values[0])
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        try {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
            val value = withTimeoutOrNull(3000) { deferred.await() }
            if (value != null) {
                context.variables[outputVar] = value
                context.variables["sensor_value"] = value
                context.variables["sensor_type"] = typeStr
                context.variables["sensor_success"] = true
                context.log("GetSensor $typeStr = $value")
            } else {
                context.variables["sensor_success"] = false
                context.variables["sensor_error"] = "no reading within 3s"
                context.log("GetSensor $typeStr TIMEOUT (no reading)")
            }
        } catch (e: Exception) {
            context.variables["sensor_success"] = false
            context.variables["sensor_error"] = e.message ?: "Unknown error"
            context.log("GetSensor FAILED: ${e.message}")
        } finally {
            runCatching { sm.unregisterListener(listener) }
            thread.quitSafely()
        }
        return node.next
    }
}
