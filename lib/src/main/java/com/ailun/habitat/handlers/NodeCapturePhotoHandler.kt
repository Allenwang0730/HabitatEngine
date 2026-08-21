package com.ailun.habitat.handlers

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * [ACTION_CAPTURE_PHOTO]：拍照并保存 JPEG（需 CAMERA 运行时权限）。
 * params：
 * - `path`（可选）：输出路径；默认 外部 filesDir/habitat_photo_<时间戳>.jpg
 * - `output_var`（可选）：路径输出变量名，默认 "photo_path"
 * 输出：photo_success、photo_path、photo_error
 *
 * 实现要点：Camera2 静默拍摄（无需 Activity/预览），自带 HandlerThread 承载回调
 * （符合无 Looper 线程约束）。相机被占用/无权限时如实返回失败。
 * 注意：属于高风险节点，建议在测试专用机/模拟器验证。
 */
class NodeCapturePhotoHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawPath = node.params?.get("path")?.toString()
        val outputVar = node.params?.get("output_var")?.toString()?.trim()?.ifEmpty { null }
            ?: "photo_path"
        val baseDir = context.appContext.getExternalFilesDir(null) ?: context.appContext.filesDir
        val path = if (!rawPath.isNullOrBlank()) {
            context.interpolate(rawPath)
        } else {
            File(baseDir, "habitat_photo_${System.currentTimeMillis()}.jpg").absolutePath
        }

        val cm = context.appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) {
            context.variables["photo_success"] = false
            context.variables["photo_error"] = "CameraManager not available"
            return node.next
        }

        val thread = HandlerThread("HabitatCamera").apply { start() }
        val handler = Handler(thread.looper)
        val imageReady = CompletableDeferred<Boolean>()

        try {
            val cameraId = cm.cameraIdList.firstOrNull()
            if (cameraId == null) {
                fail(context, "no camera available")
                return node.next
            }
            val size = pickJpegSize(cm.getCameraCharacteristics(cameraId))
            if (size == null) {
                fail(context, "no supported JPEG size")
                return node.next
            }

            val reader = android.media.ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage()
                if (image != null) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        File(path).writeBytes(bytes)
                        imageReady.complete(true)
                    } catch (e: Exception) {
                        imageReady.complete(false)
                    } finally {
                        image.close()
                    }
                } else {
                    imageReady.complete(false)
                }
            }, handler)

            val opened = CompletableDeferred<CameraDevice?>()
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { opened.complete(camera) }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); opened.complete(null) }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); opened.complete(null) }
            }, handler)

            val camera = withTimeoutOrNull(5_000) { opened.await() }
            if (camera == null) {
                reader.close()
                fail(context, "openCamera failed（相机被占用？）")
                return node.next
            }

            val sessionReady = CompletableDeferred<Boolean>()
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(reader.surface)
                                set(CaptureRequest.JPEG_QUALITY, 95)
                            }
                            session.capture(request.build(), null, handler)
                            sessionReady.complete(true)
                        } catch (e: Exception) {
                            sessionReady.complete(false)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        sessionReady.complete(false)
                    }
                },
                handler,
            )

            val configured = withTimeoutOrNull(5_000) { sessionReady.await() } ?: false
            val captured = if (configured) {
                withTimeoutOrNull(10_000) { imageReady.await() } ?: false
            } else {
                false
            }

            camera.close()
            reader.close()

            if (captured && File(path).length() > 0) {
                context.variables["photo_path"] = path
                if (outputVar != null) context.variables[outputVar] = path
                context.variables["photo_success"] = true
                context.log("CapturePhoto OK: $path")
            } else {
                fail(context, "capture failed（相机被占用或配置失败）")
            }
        } catch (e: SecurityException) {
            fail(context, "缺少 CAMERA 权限: ${e.message}")
        } catch (e: Exception) {
            fail(context, e.message ?: "Unknown error")
        } finally {
            thread.quitSafely()
        }
        return node.next
    }

    private fun fail(context: WorkflowContext, error: String) {
        context.variables["photo_success"] = false
        context.variables["photo_error"] = error
        context.log("CapturePhoto FAILED: $error")
    }

    private fun pickJpegSize(ch: CameraCharacteristics): Size? {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val best = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
            ?: return null
        // 限制到 2560 宽以内，避免大图 OOM
        return if (best.width > 2560) {
            Size(2560, (2560.0 * best.height / best.width).toInt())
        } else {
            best
        }
    }
}
