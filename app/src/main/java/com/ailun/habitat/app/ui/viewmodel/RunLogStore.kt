package com.ailun.habitat.app.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级运行日志：日志回调可能来自任意线程，Activity 重建/销毁后日志不丢。
 * 每个 key（如编辑器测试运行）最多保留 [MAX_LINES] 行。
 */
object RunLogStore {
    private const val MAX_LINES = 500
    private val flows = ConcurrentHashMap<String, MutableStateFlow<List<String>>>()

    private fun flow(key: String) = flows.getOrPut(key) { MutableStateFlow(emptyList()) }

    fun lines(key: String): StateFlow<List<String>> = flow(key).asStateFlow()

    fun append(key: String, line: String) {
        flow(key).update { (it + line).takeLast(MAX_LINES) }
    }

    fun clear(key: String) {
        flow(key).value = emptyList()
    }

    fun reset(key: String, first: String) {
        flow(key).value = listOf(first)
    }
}
