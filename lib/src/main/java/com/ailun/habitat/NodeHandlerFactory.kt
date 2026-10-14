package com.ailun.habitat

import com.ailun.habitat.api.IAccessibilityProvider
import com.ailun.habitat.api.IShellExecutor
import java.util.concurrent.ConcurrentHashMap

/**
 * 节点处理器工厂（阶段 B：模块化）。
 *
 * - `modules` 为 null（默认）→ 注册全部节点（向后兼容，老调用不传不变）；
 *   传 Set → 只注册所选模块。
 * - `extra` 可追加/覆盖单节点（宿主自定义实现，key=节点类型）。
 * - 注册表存"工厂 lambda"（闭包捕获 a11y/shell 注入），get() 时懒实例化并缓存。
 * - FORK/JOIN 由 HabitatExecutor 内建处理，不在此注册（常量见 companion）。
 */
class NodeHandlerFactory(
    private val a11y: IAccessibilityProvider? = null,
    private val shell: IShellExecutor? = null,
    modules: Set<NodeModule>? = null,
    extra: Map<String, NodeFactory> = emptyMap(),
) {

    // FORK 分支并发调用 get()：注册表与缓存需线程安全
    private val registry = ConcurrentHashMap<String, NodeFactory>()
    private val cache = ConcurrentHashMap<String, INodeHandler>()

    init {
        val selected = modules ?: NodeModule.entries.toSet()
        val merged = linkedMapOf<String, NodeFactory>()
        for ((module, entries) in ALL_NODE_MODULES) {
            if (module in selected) {
                entries.forEach { (type, factory) -> merged.putIfAbsent(type, factory) }
            }
        }
        // 宿主注入/覆盖（优先于内置）
        extra.forEach { (type, factory) -> merged[type] = factory }
        merged.forEach { (type, factory) -> registry[type] = factory }
    }

    /** 注册（保留旧 API，app 侧 applyAppHandlers 使用；覆盖已存在类型）。 */
    fun register(type: String, handler: INodeHandler) {
        registry[type] = { _, _ -> handler }
        cache.remove(type)
    }

    /** 懒实例化：首次 get 时创建并缓存。 */
    fun get(type: String?): INodeHandler? {
        if (type == null) return null
        cache[type]?.let { return it }
        val factory = registry[type] ?: return null
        // 并发首次 get 时只保留一个实例
        return cache.putIfAbsent(type, factory(a11y, shell)) ?: cache[type]
    }

    /** 当前注册的全部节点类型（调试/文档/LLM 生成可用）。 */
    fun availableTypes(): Set<String> = registry.keys.toSet()

    /** 当前注册的类型数量。 */
    val size: Int get() = registry.size

    companion object {
        const val CONDITION_SWITCH = "CONDITION_SWITCH"
        const val CONDITION_ADVANCED_SWITCH = "CONDITION_ADVANCED_SWITCH"
        const val ACTION_DELAY = "ACTION_DELAY"
        const val ACTION_LOOP = "ACTION_LOOP"
        const val ACTION_TRY_CATCH = "ACTION_TRY_CATCH"
        const val ACTION_LOG = "ACTION_LOG"
        const val ACTION_SET_VARIABLE = "ACTION_SET_VARIABLE"
        const val ACTION_TEXT_OPERATION = "ACTION_TEXT_OPERATION"
        const val ACTION_MATH = "ACTION_MATH"
        const val ACTION_CLIPBOARD = "ACTION_CLIPBOARD"
        const val ACTION_PARSE_JSON = "ACTION_PARSE_JSON"
        const val ACTION_PARSE_XML = "ACTION_PARSE_XML"
        const val ACTION_BASE64 = "ACTION_BASE64"
        const val ACTION_FILE_OPERATION = "ACTION_FILE_OPERATION"
        const val ACTION_SQLITE_QUERY = "ACTION_SQLITE_QUERY"
        const val ACTION_ZIP = "ACTION_ZIP"
        const val ACTION_CRYPTO = "ACTION_CRYPTO"
        const val ACTION_ARRAY = "ACTION_ARRAY"
        const val ACTION_PARSE_DATETIME = "ACTION_PARSE_DATETIME"
        const val ACTION_CLICK = "ACTION_CLICK"
        const val ACTION_SWIPE = "ACTION_SWIPE"
        const val ACTION_LONG_PRESS = "ACTION_LONG_PRESS"
        const val ACTION_INPUT_TEXT = "ACTION_INPUT_TEXT"
        const val ACTION_GLOBAL_KEY = "ACTION_GLOBAL_KEY"
        const val ACTION_FIND_ELEMENT = "ACTION_FIND_ELEMENT"
        const val ACTION_SHELL = "ACTION_SHELL"
        const val ACTION_LAUNCH_APP = "ACTION_LAUNCH_APP"
        const val ACTION_FORCE_STOP_APP = "ACTION_FORCE_STOP_APP"
        const val ACTION_SCREEN_WAKE = "ACTION_SCREEN_WAKE"
        const val ACTION_WIFI = "ACTION_WIFI"
        const val ACTION_BLUETOOTH = "ACTION_BLUETOOTH"
        const val ACTION_VOLUME = "ACTION_VOLUME"
        const val ACTION_BRIGHTNESS = "ACTION_BRIGHTNESS"
        const val ACTION_FLASHLIGHT = "ACTION_FLASHLIGHT"
        const val ACTION_CALL_PHONE = "ACTION_CALL_PHONE"
        const val ACTION_CALL_DIRECT = "ACTION_CALL_DIRECT"
        const val ACTION_WAIT_CALL_END = "ACTION_WAIT_CALL_END"
        const val ACTION_WAIT_CALL_ANSWER = "ACTION_WAIT_CALL_ANSWER"
        const val ACTION_SHARE = "ACTION_SHARE"
        const val ACTION_GET_CALL_STATE = "ACTION_GET_CALL_STATE"
        const val ACTION_GET_SIM_INFO = "ACTION_GET_SIM_INFO"
        const val ACTION_SET_ALARM = "ACTION_SET_ALARM"
        const val ACTION_DND_MODE = "ACTION_DND_MODE"
        const val ACTION_GLOBAL_NAV = "ACTION_GLOBAL_NAV"
        const val ACTION_WAIT_FOR_ELEMENT = "ACTION_WAIT_FOR_ELEMENT"
        const val ACTION_RECORD_AUDIO = "ACTION_RECORD_AUDIO"
        const val ACTION_CAPTURE_PHOTO = "ACTION_CAPTURE_PHOTO"
        const val ACTION_GET_LOCATION = "ACTION_GET_LOCATION"
        const val ACTION_READ_NOTIFICATIONS = "ACTION_READ_NOTIFICATIONS"
        const val ACTION_SCREENSHOT = "ACTION_SCREENSHOT"
        const val ACTION_TEXT_TO_SPEECH = "ACTION_TEXT_TO_SPEECH"
        const val ACTION_HTTP_REQUEST = "ACTION_HTTP_REQUEST"
        const val ACTION_NETWORK_STATUS = "ACTION_NETWORK_STATUS"
        const val ACTION_GET_TIME = "ACTION_GET_TIME"
        const val ACTION_RANDOM = "ACTION_RANDOM"
        const val ACTION_GET_BATTERY = "ACTION_GET_BATTERY"
        const val ACTION_GET_DEVICE_INFO = "ACTION_GET_DEVICE_INFO"
        const val ACTION_GET_STORAGE_INFO = "ACTION_GET_STORAGE_INFO"
        const val ACTION_PLAY_MEDIA = "ACTION_PLAY_MEDIA"
        const val ACTION_WAIT_UNTIL_TIME = "ACTION_WAIT_UNTIL_TIME"
        const val ACTION_GET_SENSOR = "ACTION_GET_SENSOR"
        const val ACTION_READ_SCREEN = "ACTION_READ_SCREEN"
        const val ACTION_READ_SMS = "ACTION_READ_SMS"
        const val ACTION_READ_CALL_LOG = "ACTION_READ_CALL_LOG"
        const val ACTION_GET_APP_INFO = "ACTION_GET_APP_INFO"
        const val ACTION_FOREGROUND_APP = "ACTION_FOREGROUND_APP"
        const val ACTION_APP_SEARCH = "ACTION_APP_SEARCH"
        const val ACTION_TOAST = "ACTION_TOAST"
        const val ACTION_VIBRATE = "ACTION_VIBRATE"
        const val ACTION_SEND_NOTIFICATION = "ACTION_SEND_NOTIFICATION"
        const val ACTION_DYNAMIC_ISLAND = "ACTION_DYNAMIC_ISLAND"
        const val ACTION_AI_CHAT = "ACTION_AI_CHAT"
        // 异常处理（v1.4.2）
        const val ACTION_RETRY = "ACTION_RETRY"
        const val ACTION_ASSERT = "ACTION_ASSERT"
        // 通用查询（v1.4.2）
        const val ACTION_GET_SCREEN_STATE = "ACTION_GET_SCREEN_STATE"
        const val ACTION_GET_AUDIO_STATE = "ACTION_GET_AUDIO_STATE"
        // 系统/媒体（v1.4.2）
        const val ACTION_SEND_BROADCAST = "ACTION_SEND_BROADCAST"
        const val ACTION_MEDIA_CONTROL = "ACTION_MEDIA_CONTROL"
        const val ACTION_GET_WEATHER = "ACTION_GET_WEATHER"
        const val ACTION_SET_WALLPAPER = "ACTION_SET_WALLPAPER"
        const val ACTION_GEOCODE = "ACTION_GEOCODE"
        const val ACTION_SAY_TO_FILE = "ACTION_SAY_TO_FILE"
        const val ACTION_GET_TRAFFIC = "ACTION_GET_TRAFFIC"
        const val ACTION_GET_SETTING = "ACTION_GET_SETTING"
        // FORK/JOIN 由 HabitatExecutor 内建处理（不注册 handler，仅声明常量供白名单/LLM）
        const val ACTION_FORK = HabitatExecutor.ACTION_FORK
        const val ACTION_JOIN = HabitatExecutor.ACTION_JOIN
    }
}
