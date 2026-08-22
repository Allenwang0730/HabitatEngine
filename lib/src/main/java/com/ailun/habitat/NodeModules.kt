package com.ailun.habitat

import com.ailun.habitat.api.IAccessibilityProvider
import com.ailun.habitat.api.IShellExecutor
import com.ailun.habitat.handlers.*

/**
 * 节点模块化（阶段 B）。
 *
 * 每个模块是一个编译期静态注册表（替代注解处理/KSP：效果相同、零构建成本、无反射、R8 安全）。
 * 工厂签名统一为 (a11y, shell) -> INodeHandler，由 NodeHandlerFactory 在注册时闭包捕获注入依赖，
 * get() 时懒实例化。
 *
 * 模块语义：
 * - CORE：编程基础 + 通用查询（逻辑/变量/数据/工具/基础 UI）——建议任何嵌入方都引入
 * - INTERACTION / SYSTEM / TELEPHONY / MEDIA / NETWORK / SENSOR / AI：按需领域
 */
enum class NodeModule {
    CORE, INTERACTION, SYSTEM, TELEPHONY, MEDIA, NETWORK, SENSOR, AI,
}

/** 节点工厂签名：(无障碍服务, shell执行器) -> handler 实例 */
typealias NodeFactory = (IAccessibilityProvider?, IShellExecutor?) -> INodeHandler

/** CORE：编程基础 + 通用查询（逻辑控制 / 变量数据 / 工具 / 基础 UI） */
object CoreNodes {
    val entries: List<Pair<String, NodeFactory>> = listOf(
        // 逻辑控制
        NodeHandlerFactory.CONDITION_SWITCH to { _, _ -> SwitchNodeHandler() },
        NodeHandlerFactory.CONDITION_ADVANCED_SWITCH to { _, _ -> NodeAdvancedSwitchHandler() },
        NodeHandlerFactory.ACTION_DELAY to { _, _ -> NodeDelayHandler() },
        NodeHandlerFactory.ACTION_LOOP to { _, _ -> NodeLoopHandler() },
        NodeHandlerFactory.ACTION_TRY_CATCH to { _, _ -> NodeTryCatchHandler() },
        NodeHandlerFactory.ACTION_LOG to { _, _ -> NodeLogHandler() },
        // 变量与数据
        NodeHandlerFactory.ACTION_SET_VARIABLE to { _, _ -> NodeSetVariableHandler() },
        NodeHandlerFactory.ACTION_TEXT_OPERATION to { _, _ -> NodeTextOperationHandler() },
        NodeHandlerFactory.ACTION_MATH to { _, _ -> NodeMathHandler() },
        NodeHandlerFactory.ACTION_CLIPBOARD to { _, _ -> NodeClipboardHandler() },
        NodeHandlerFactory.ACTION_PARSE_JSON to { _, _ -> NodeParseJsonHandler() },
        NodeHandlerFactory.ACTION_PARSE_XML to { _, _ -> NodeParseXmlHandler() },
        NodeHandlerFactory.ACTION_BASE64 to { _, _ -> NodeBase64Handler() },
        NodeHandlerFactory.ACTION_FILE_OPERATION to { _, _ -> NodeFileOperationHandler() },
        NodeHandlerFactory.ACTION_SQLITE_QUERY to { _, _ -> NodeSqliteQueryHandler() },
        NodeHandlerFactory.ACTION_ZIP to { _, _ -> NodeZipHandler() },
        NodeHandlerFactory.ACTION_CRYPTO to { _, _ -> NodeCryptoHandler() },
        NodeHandlerFactory.ACTION_ARRAY to { _, _ -> NodeArrayHandler() },
        NodeHandlerFactory.ACTION_PARSE_DATETIME to { _, _ -> NodeParseDateTimeHandler() },
        // 工具/通用查询
        NodeHandlerFactory.ACTION_GET_TIME to { _, _ -> NodeGetTimeHandler() },
        NodeHandlerFactory.ACTION_RANDOM to { _, _ -> NodeRandomHandler() },
        NodeHandlerFactory.ACTION_WAIT_UNTIL_TIME to { _, _ -> NodeWaitUntilTimeHandler() },
        NodeHandlerFactory.ACTION_GET_BATTERY to { _, _ -> NodeGetBatteryHandler() },
        NodeHandlerFactory.ACTION_GET_DEVICE_INFO to { _, _ -> NodeGetDeviceInfoHandler() },
        NodeHandlerFactory.ACTION_GET_STORAGE_INFO to { _, _ -> NodeGetStorageInfoHandler() },
        NodeHandlerFactory.ACTION_GET_SENSOR to { _, _ -> NodeGetSensorHandler() },
        NodeHandlerFactory.ACTION_GET_SCREEN_STATE to { _, _ -> NodeGetScreenStateHandler() },
        NodeHandlerFactory.ACTION_GET_AUDIO_STATE to { _, _ -> NodeGetAudioStateHandler() },
        // 异常处理
        NodeHandlerFactory.ACTION_RETRY to { _, _ -> NodeRetryHandler() },
        NodeHandlerFactory.ACTION_ASSERT to { _, _ -> NodeAssertHandler() },
        // 基础 UI
        NodeHandlerFactory.ACTION_TOAST to { _, _ -> ToastNodeHandler() },
        NodeHandlerFactory.ACTION_VIBRATE to { _, _ -> VibrateNodeHandler() },
    )
}

/** INTERACTION：无障碍交互 */
object InteractionNodes {
    val entries: List<Pair<String, NodeFactory>> = listOf(
        NodeHandlerFactory.ACTION_CLICK to { a, _ -> NodeClickHandler(a) },
        NodeHandlerFactory.ACTION_SWIPE to { a, _ -> NodeSwipeHandler(a) },
        NodeHandlerFactory.ACTION_LONG_PRESS to { a, s -> NodeLongPressHandler(a, s) },
        NodeHandlerFactory.ACTION_INPUT_TEXT to { a, s -> NodeInputTextHandler(a, s) },
        NodeHandlerFactory.ACTION_GLOBAL_KEY to { a, s -> NodeGlobalKeyHandler(a, s) },
        NodeHandlerFactory.ACTION_FIND_ELEMENT to { a, s -> NodeFindElementHandler(a, s) },
        NodeHandlerFactory.ACTION_GLOBAL_NAV to { a, _ -> NodeGlobalNavHandler(a) },
        NodeHandlerFactory.ACTION_WAIT_FOR_ELEMENT to { a, _ -> NodeWaitForElementHandler(a) },
    )
}

/** SYSTEM：系统控制 */
object SystemNodes {
    val entries: List<Pair<String, NodeFactory>> = listOf(
        NodeHandlerFactory.ACTION_SHELL to { _, s -> NodeShellHandler(s) },
        NodeHandlerFactory.ACTION_LAUNCH_APP to { _, _ -> NodeLaunchAppHandler() },
        NodeHandlerFactory.ACTION_FORCE_STOP_APP to { _, s -> NodeForceStopAppHandler(shellExecutor = s) },
        NodeHandlerFactory.ACTION_SCREEN_WAKE to { _, s -> NodeScreenWakeHandler(shellExecutor = s) },
        NodeHandlerFactory.ACTION_WIFI to { _, _ -> NodeWifiHandler() },
        NodeHandlerFactory.ACTION_BLUETOOTH to { _, _ -> NodeBluetoothHandler() },
        NodeHandlerFactory.ACTION_VOLUME to { _, _ -> NodeVolumeHandler() },
        NodeHandlerFactory.ACTION_BRIGHTNESS to { _, _ -> NodeBrightnessHandler() },
        NodeHandlerFactory.ACTION_FLASHLIGHT to { _, _ -> NodeFlashlightHandler() },
        NodeHandlerFactory.ACTION_SET_ALARM to { _, _ -> NodeSetAlarmHandler() },
        NodeHandlerFactory.ACTION_DND_MODE to { _, _ -> NodeDndModeHandler() },
        NodeHandlerFactory.ACTION_SHARE to { _, _ -> NodeShareHandler() },
        NodeHandlerFactory.ACTION_SEND_BROADCAST to { _, _ -> NodeSendBroadcastHandler() },
        NodeHandlerFactory.ACTION_MEDIA_CONTROL to { _, s -> NodeMediaControlHandler(s) },
    )
}

/** TELEPHONY：通话相关 */
object TelephonyNodes {
    val entries: List<Pair<String, NodeFactory>> = listOf(
        NodeHandlerFactory.ACTION_CALL_PHONE to { _, _ -> NodeCallHandler() },
        NodeHandlerFactory.ACTION_CALL_DIRECT to { _, _ -> NodeCallDirectHandler() },
        NodeHandlerFactory.ACTION_WAIT_CALL_END to { _, _ -> NodeWaitCallEndHandler() },
        NodeHandlerFactory.ACTION_WAIT_CALL_ANSWER to { _, _ -> NodeWaitCallAnswerHandler() },
        NodeHandlerFactory.ACTION_GET_CALL_STATE to { _, _ -> NodeGetCallStateHandler() },
        NodeHandlerFactory.ACTION_GET_SIM_INFO to { _, _ -> NodeGetSimInfoHandler() },
        NodeHandlerFactory.ACTION_READ_CALL_LOG to { _, _ -> NodeReadCallLogHandler() },
    )
}

/** MEDIA：媒体 */
object MediaNodes {
    val entries: List<Pair<String, NodeFactory>> = listOf(
        NodeHandlerFactory.ACTION_SCREENSHOT to { _, s -> NodeScreenshotHandler(s) },
        NodeHandlerFactory.ACTION_TEXT_TO_SPEECH to { _, _ -> NodeTtsHandler() },
        NodeHandlerFactory.ACTION_PLAY_MEDIA to { _, _ -> NodePlayMediaHandler() },
        NodeHandlerFactory.ACTION_RECORD_AUDIO to { _, _ -> NodeRecordAudioHandler() },
        NodeHandlerFactory.ACTION_CAPTURE_PHOTO to { _, _ -> NodeCapturePhotoHandler() },
        NodeHandlerFactory.ACTION_SET_WALLPAPER to { _, _ -> NodeSetWallpaperHandler() },
    )
}

/** NETWORK：网络 */
object NetworkNodes {
    val entries: List<Pair<String, NodeFactory>> = listOf(
        NodeHandlerFactory.ACTION_HTTP_REQUEST to { _, _ -> NodeHttpHandler() },
        NodeHandlerFactory.ACTION_NETWORK_STATUS to { _, _ -> NodeNetworkStatusHandler() },
        NodeHandlerFactory.ACTION_GET_WEATHER to { _, _ -> NodeGetWeatherHandler() },
    )
}

/** SENSOR：感知 */
object SensorNodes {
    val entries: List<Pair<String, NodeFactory>> = listOf(
        NodeHandlerFactory.ACTION_READ_SCREEN to { a, _ -> NodeReadScreenHandler(a) },
        NodeHandlerFactory.ACTION_READ_SMS to { _, s -> NodeReadSmsHandler(s) },
        NodeHandlerFactory.ACTION_GET_APP_INFO to { a, _ -> NodeAppInfoHandler(a) },
        NodeHandlerFactory.ACTION_FOREGROUND_APP to { a, s -> NodeForegroundAppHandler(a, s) },
        NodeHandlerFactory.ACTION_APP_SEARCH to { _, _ -> NodeAppSearchHandler() },
        NodeHandlerFactory.ACTION_GET_LOCATION to { _, _ -> NodeGetLocationHandler() },
        NodeHandlerFactory.ACTION_READ_NOTIFICATIONS to { _, _ -> NodeReadNotificationsHandler() },
    )
}

/** AI */
object AiNodes {
    val entries: List<Pair<String, NodeFactory>> = listOf(
        NodeHandlerFactory.ACTION_AI_CHAT to { _, _ -> NodeLLMHandler(llmService = null) },
    )
}

/** 全部模块的注册表（供默认全量注册） */
val ALL_NODE_MODULES: Map<NodeModule, List<Pair<String, NodeFactory>>> = mapOf(
    NodeModule.CORE to CoreNodes.entries,
    NodeModule.INTERACTION to InteractionNodes.entries,
    NodeModule.SYSTEM to SystemNodes.entries,
    NodeModule.TELEPHONY to TelephonyNodes.entries,
    NodeModule.MEDIA to MediaNodes.entries,
    NodeModule.NETWORK to NetworkNodes.entries,
    NodeModule.SENSOR to SensorNodes.entries,
    NodeModule.AI to AiNodes.entries,
)
