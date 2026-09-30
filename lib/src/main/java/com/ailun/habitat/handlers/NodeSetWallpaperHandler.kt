package com.ailun.habitat.handlers

import android.app.WallpaperManager
import android.graphics.BitmapFactory
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_SET_WALLPAPER]：设置主屏壁纸（需 SET_WALLPAPER 普通权限，Manifest 声明即可）。
 * params：
 * - `path`（必填）：图片文件路径，支持 ${var}
 * - `output_var`（可选）：成功标志变量名，默认 "wallpaper_success"
 * 输出：wallpaper_success、wallpaper_error
 */
class NodeSetWallpaperHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val rawPath = node.params?.get("path")?.toString()?.trim().orEmpty()
        if (rawPath.isEmpty()) {
            context.putVariable("wallpaper_success", false)
            context.putVariable("wallpaper_error", "缺少 'path' 参数")
            return node.next
        }
        val path = context.interpolate(rawPath)

        try {
            val bitmap = BitmapFactory.decodeFile(path)
                ?: throw IllegalArgumentException("无法解码图片: $path")
            val wm = WallpaperManager.getInstance(context.appContext)
            wm.setBitmap(bitmap)
            context.putVariable("wallpaper_success", true)
            context.log("SetWallpaper OK: $path")
        } catch (e: SecurityException) {
            context.putVariable("wallpaper_success", false)
            context.putVariable("wallpaper_error", "缺少 SET_WALLPAPER 权限: ${e.message}")
            context.log("SetWallpaper FAILED (SET_WALLPAPER missing)")
        } catch (e: Exception) {
            context.putVariable("wallpaper_success", false)
            context.putVariable("wallpaper_error", e.message ?: "Unknown error")
            context.log("SetWallpaper FAILED: ${e.message}")
        }
        return node.next
    }
}
