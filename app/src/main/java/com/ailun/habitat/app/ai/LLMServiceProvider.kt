package com.ailun.habitat.app.ai

import android.content.Context
import com.ailun.habitat.ai.ILLMService

object LLMServiceProvider {
    @Volatile
    private var instance: ILLMService? = null

    /** 单例进程级存活：只保存 applicationContext，避免持有传入的 Activity。 */
    fun get(context: Context): ILLMService =
        instance ?: synchronized(this) {
            instance ?: LiteRtLLMServiceImpl(context.applicationContext).also { instance = it }
        }
}
