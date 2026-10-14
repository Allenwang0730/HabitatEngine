package com.ailun.habitat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ailun.habitat.HabitatStateStore
import com.ailun.habitat.app.ui.AIWorkflowScreen
import com.ailun.habitat.app.ui.AgentScreen
import com.ailun.habitat.app.ui.HabitatSettingsActivity
import com.ailun.habitat.app.ui.ProfileTab
import com.ailun.habitat.app.ui.screens.LlmConsoleScreen
import com.ailun.habitat.app.ui.screens.WorkflowEditorScreen
import com.ailun.habitat.app.ui.screens.WorkflowListScreen
import com.ailun.habitat.app.ui.viewmodel.DashboardViewModel

// ── 底部导航 ──
private enum class Tab(val label: String, val selectedIcon: ImageVector, val icon: ImageVector) {
    Workflows("工作流", Icons.Filled.AccountTree, Icons.Outlined.AccountTree),
    AIGen("AI 生成", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    Agent("Agent", Icons.Filled.SmartToy, Icons.Outlined.SmartToy),
    Profile("我的", Icons.Filled.Person, Icons.Outlined.Person),
}

/** 当前主内容：某个 tab，或覆盖在 tab 之上的全屏页（编辑器 / LLM 控制台）。 */
private sealed interface Destination {
    data class Home(val tab: Tab) : Destination
    data object Editor : Destination
    data object Llm : Destination
}

/**
 * 根界面。Activity 侧负责 enableEdgeToEdge + HabitatTheme；这里处理 insets：
 * 外层 Scaffold 只负责底部导航（contentWindowInsets=0），各页面自己的 Scaffold/TopAppBar 处理状态栏与 IME。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitatDashboard(activity: ComponentActivity) {
    val vm = remember { ViewModelProvider(activity)[DashboardViewModel::class.java] }
    var selectedTab by rememberSaveable { mutableStateOf(Tab.Workflows) }
    var showLlm by rememberSaveable { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()

    // ── 悬浮窗开关 ──
    val floatActiveFromStore by HabitatStateStore.floatServiceActive.collectAsStateWithLifecycle()
    var floatServiceRunning by remember { mutableStateOf(HabitatFloatService.isRunning) }
    // floatActiveFromStore 初值 false（内存默认）不代表服务停止：只在明确开启或确实已停止时同步
    LaunchedEffect(floatActiveFromStore) {
        if (floatActiveFromStore && !floatServiceRunning) floatServiceRunning = true
        else if (!floatActiveFromStore && floatServiceRunning && !HabitatFloatService.isRunning) floatServiceRunning = false
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(activity)) {
            HabitatFloatService.start(activity)
            FloatWindowManager.getInstance(activity.application).showFloatWindow()
            floatServiceRunning = true
            HabitatStateStore.setFloatServiceActive(true)
            Toast.makeText(activity, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
        }
    }
    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(activity, if (granted) "已授予通话权限，请重新运行" else "未授予通话权限，拨号节点将无法执行", Toast.LENGTH_SHORT).show()
    }

    fun ensureFloatService() {
        if (!Settings.canDrawOverlays(activity)) {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")),
            )
            return
        }
        if (!HabitatFloatService.isRunning) {
            HabitatFloatService.start(activity)
            HabitatStateStore.setFloatServiceActive(true)
            Toast.makeText(activity, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
        }
        floatServiceRunning = true
    }

    fun turnOffFloatService() {
        HabitatFloatService.stop(activity)
        FloatWindowManager.getInstanceOrNull()?.hideFloatWindow()
        floatServiceRunning = false
        HabitatStateStore.setFloatServiceActive(false)
        Toast.makeText(activity, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
    }

    val onFloatToggle: (Boolean) -> Unit = { on -> if (on) ensureFloatService() else turnOffFloatService() }

    /** 拨号类工作流运行前确认 CALL_PHONE（未授予则申请并中止本次运行）。 */
    fun callPermissionOk(json: String): Boolean {
        if (!DashboardViewModel.needsCallPermission(json)) return true
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) return true
        callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        return false
    }

    val destination: Destination = when {
        vm.session != null && selectedTab == Tab.Workflows -> Destination.Editor
        showLlm && selectedTab == Tab.Profile -> Destination.Llm
        else -> Destination.Home(selectedTab)
    }
    val fullScreen = destination !is Destination.Home
    val imeVisible = WindowInsets.isImeVisible

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(
                visible = !fullScreen && !imeVisible,
                enter = slideInVertically(Motion.offset) { it } + fadeIn(Motion.fade()),
                exit = slideOutVertically(Motion.offset) { it } + fadeOut(Motion.exit()),
            ) {
                HabitatBottomBar(selectedTab) { tab ->
                    if (tab == selectedTab && tab == Tab.Profile) showLlm = false
                    selectedTab = tab
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            AnimatedContent(
                targetState = destination,
                transitionSpec = {
                    val from = (initialState as? Destination.Home)?.tab?.ordinal
                    val to = (targetState as? Destination.Home)?.tab?.ordinal
                    if (from != null && to != null && from != to) {
                        val dir = if (to > from) 1 else -1
                        (fadeIn(Motion.fade()) + slideInHorizontally(Motion.offset) { dir * it / 10 })
                            .togetherWith(fadeOut(Motion.exit()) + slideOutHorizontally(Motion.offset) { -dir * it / 10 })
                    } else {
                        // 进入/退出全屏页：纵向轻推
                        val entering = targetState !is Destination.Home
                        (fadeIn(Motion.fade()) + slideInVertically(Motion.offset) { if (entering) it / 12 else -it / 24 })
                            .togetherWith(fadeOut(Motion.exit()))
                    }
                },
                label = "dashboard",
            ) { dest ->
                when (dest) {
                    Destination.Editor -> WorkflowEditorScreen(
                        vm = vm,
                        onClose = { vm.closeEditor() },
                        onTestRun = { json -> if (callPermissionOk(json)) vm.testRun(json) },
                    )
                    Destination.Llm -> LlmConsoleScreen(onBack = { showLlm = false })
                    is Destination.Home -> stateHolder.SaveableStateProvider(dest.tab.name) {
                        when (dest.tab) {
                            Tab.Workflows -> WorkflowListScreen(
                                vm = vm,
                                isFloatServiceRunning = floatServiceRunning,
                                onFloatServiceToggle = onFloatToggle,
                                onEdit = { vm.openEditor(it.workflow) },
                                onNew = { vm.newWorkflow() },
                                onMounted = { ensureFloatService() },
                                onRun = { item -> if (callPermissionOk(item.workflow.jsonContent)) vm.run(item) },
                            )
                            Tab.AIGen -> AIWorkflowScreen(onBack = { selectedTab = Tab.Workflows })
                            Tab.Agent -> AgentScreen()
                            Tab.Profile -> ProfileTab(
                                isFloatServiceRunning = floatServiceRunning,
                                onFloatServiceToggle = onFloatToggle,
                                onNavigateToLLM = { showLlm = true },
                                onNavigateToPermissions = {
                                    activity.startActivity(Intent(activity, HabitatSettingsActivity::class.java))
                                },
                                onNavigateToWorkflowAI = { selectedTab = Tab.AIGen },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HabitatBottomBar(selectedTab: Tab, onTabSelected: (Tab) -> Unit) {
    NavigationBar {
        Tab.entries.forEach { tab ->
            val selected = tab == selectedTab
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = {
                    Crossfade(selected, animationSpec = Motion.fade(), label = "navIcon") { s ->
                        Icon(if (s) tab.selectedIcon else tab.icon, contentDescription = tab.label)
                    }
                },
                label = { Text(tab.label) },
                alwaysShowLabel = true,
            )
        }
    }
}
