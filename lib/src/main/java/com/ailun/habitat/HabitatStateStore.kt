package com.ailun.habitat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局状态存储 — 悬浮窗和应用界面共享同一数据源，
 * 解决状态不一致和工作流列表不同步问题。
 */
object HabitatStateStore {

    /** 工作流执行状态：key=workflowId, value=状态 */
    data class WorkflowRunState(
        val workflowId: String,
        val isRunning: Boolean,
        val stepCount: Int = 0,
    )

    /** 当前所有运行中的工作流状态 */
    private val _runningStates = MutableStateFlow<Map<String, WorkflowRunState>>(emptyMap())
    val runningStates: StateFlow<Map<String, WorkflowRunState>> = _runningStates.asStateFlow()

    /** 工作流列表版本号 — 每次增删改工作流时递增，悬浮窗观察此值自动刷新 */
    private val _libraryVersion = MutableStateFlow(0L)
    val libraryVersion: StateFlow<Long> = _libraryVersion.asStateFlow()

    /** 悬浮窗可见性，供主界面与悬浮窗双向同步。 */
    private val _floatServiceActive = MutableStateFlow(false)
    val floatServiceActive: StateFlow<Boolean> = _floatServiceActive.asStateFlow()

    fun setRunning(workflowId: String, isRunning: Boolean, stepCount: Int = 0) {
        val current = _runningStates.value.toMutableMap()
        if (isRunning) {
            current[workflowId] = WorkflowRunState(workflowId, true, stepCount)
        } else {
            current.remove(workflowId)
        }
        _runningStates.value = current
    }

    fun setFloatServiceActive(active: Boolean) {
        _floatServiceActive.value = active
    }

    fun notifyLibraryChanged() {
        _libraryVersion.value = System.currentTimeMillis()
    }
}
