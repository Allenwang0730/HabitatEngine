package com.ailun.habitat.handlers

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode

/**
 * [ACTION_GET_AUDIO_STATE]：读取音频状态（零权限）。
 * 输出：music_active(Boolean，是否有音乐播放)、headset_connected(Boolean，是否插着耳机)、
 *       audio_state_success
 */
class NodeGetAudioStateHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        try {
            val am = context.appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            val musicActive = am?.isMusicActive ?: false

            val headset = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { d ->
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            } ?: false

            context.variables["music_active"] = musicActive
            context.variables["headset_connected"] = headset
            context.variables["audio_state_success"] = true
            context.log("AudioState music=$musicActive headset=$headset")
        } catch (e: Exception) {
            context.variables["audio_state_success"] = false
            context.variables["audio_state_error"] = e.message ?: "Unknown error"
        }
        return node.next
    }
}
