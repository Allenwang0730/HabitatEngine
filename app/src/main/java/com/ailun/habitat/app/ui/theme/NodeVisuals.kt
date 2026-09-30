package com.ailun.habitat.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.ui.graphics.vector.ImageVector
import com.ailun.habitat.ALL_NODE_MODULES
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.NodeModule
import com.ailun.habitat.TriggerConfig

/** 节点类别（DAG 与列表共用的视觉分类）。 */
enum class NodeCategory(val label: String) {
    TRIGGER("起始"),
    LOGIC("逻辑"),
    DATA("数据"),
    INTERACTION("交互"),
    FEEDBACK("提示"),
    SYSTEM("系统"),
    TELEPHONY("通话"),
    MEDIA("媒体"),
    NETWORK("网络"),
    SENSOR("感知"),
    AI("AI"),
    UTILITY("工具"),
}

object NodeCategories {
    private val moduleByType: Map<String, NodeModule> by lazy {
        buildMap { ALL_NODE_MODULES.forEach { (m, entries) -> entries.forEach { (t, _) -> putIfAbsent(t, m) } } }
    }

    private val logicTypes = setOf(
        NodeHandlerFactory.CONDITION_SWITCH, NodeHandlerFactory.CONDITION_ADVANCED_SWITCH,
        NodeHandlerFactory.ACTION_DELAY, NodeHandlerFactory.ACTION_LOOP, NodeHandlerFactory.ACTION_TRY_CATCH,
        NodeHandlerFactory.ACTION_RETRY, NodeHandlerFactory.ACTION_ASSERT, NodeHandlerFactory.ACTION_FORK,
        NodeHandlerFactory.ACTION_JOIN, NodeHandlerFactory.ACTION_WAIT_UNTIL_TIME,
    )
    private val dataTypes = setOf(
        NodeHandlerFactory.ACTION_SET_VARIABLE, NodeHandlerFactory.ACTION_TEXT_OPERATION, NodeHandlerFactory.ACTION_MATH,
        NodeHandlerFactory.ACTION_CLIPBOARD, NodeHandlerFactory.ACTION_PARSE_JSON, NodeHandlerFactory.ACTION_PARSE_XML,
        NodeHandlerFactory.ACTION_BASE64, NodeHandlerFactory.ACTION_FILE_OPERATION, NodeHandlerFactory.ACTION_SQLITE_QUERY,
        NodeHandlerFactory.ACTION_ZIP, NodeHandlerFactory.ACTION_CRYPTO, NodeHandlerFactory.ACTION_ARRAY,
        NodeHandlerFactory.ACTION_PARSE_DATETIME,
    )
    private val feedbackTypes = setOf(
        NodeHandlerFactory.ACTION_TOAST, NodeHandlerFactory.ACTION_VIBRATE, NodeHandlerFactory.ACTION_SEND_NOTIFICATION,
        NodeHandlerFactory.ACTION_DYNAMIC_ISLAND, NodeHandlerFactory.ACTION_LOG, NodeHandlerFactory.ACTION_TEXT_TO_SPEECH,
    )

    fun of(type: String?): NodeCategory {
        val t = type ?: return NodeCategory.UTILITY
        return when {
            t in logicTypes || t.startsWith("CONDITION_") -> NodeCategory.LOGIC
            t in dataTypes -> NodeCategory.DATA
            t in feedbackTypes -> NodeCategory.FEEDBACK
            t == NodeHandlerFactory.ACTION_AI_CHAT -> NodeCategory.AI
            else -> when (moduleByType[t]) {
                NodeModule.INTERACTION -> NodeCategory.INTERACTION
                NodeModule.SYSTEM -> NodeCategory.SYSTEM
                NodeModule.TELEPHONY -> NodeCategory.TELEPHONY
                NodeModule.MEDIA -> NodeCategory.MEDIA
                NodeModule.NETWORK -> NodeCategory.NETWORK
                NodeModule.SENSOR -> NodeCategory.SENSOR
                NodeModule.AI -> NodeCategory.AI
                else -> NodeCategory.UTILITY
            }
        }
    }

    fun icon(category: NodeCategory): ImageVector = when (category) {
        NodeCategory.TRIGGER -> Icons.Outlined.Bolt
        NodeCategory.LOGIC -> Icons.AutoMirrored.Outlined.AltRoute
        NodeCategory.DATA -> Icons.Outlined.DataObject
        NodeCategory.INTERACTION -> Icons.Outlined.TouchApp
        NodeCategory.FEEDBACK -> Icons.Outlined.NotificationsActive
        NodeCategory.SYSTEM -> Icons.Outlined.SettingsSuggest
        NodeCategory.TELEPHONY -> Icons.Outlined.Call
        NodeCategory.MEDIA -> Icons.Outlined.PermMedia
        NodeCategory.NETWORK -> Icons.Outlined.Public
        NodeCategory.SENSOR -> Icons.Outlined.Sensors
        NodeCategory.AI -> Icons.Outlined.AutoAwesome
        NodeCategory.UTILITY -> Icons.Outlined.Build
    }

    /** ACTION_LAUNCH_APP → "Launch app"。 */
    fun displayName(type: String): String {
        val core = type.removePrefix("ACTION_").removePrefix("CONDITION_")
        return core.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** 触发类型的展示信息。type == null 表示手动运行。 */
object TriggerVisuals {
    const val MANUAL = "manual"

    fun label(type: String?): String = when (type) {
        null, MANUAL -> "手动运行"
        TriggerConfig.TYPE_NOTIFICATION -> "通知触发"
        TriggerConfig.TYPE_SMS -> "短信触发"
        TriggerConfig.TYPE_TIMER -> "定时触发"
        TriggerConfig.TYPE_CLIPBOARD -> "剪贴板触发"
        TriggerConfig.TYPE_KEY -> "按键触发"
        else -> "触发：$type"
    }

    fun shortLabel(type: String?): String = when (type) {
        null, MANUAL -> "手动"
        TriggerConfig.TYPE_NOTIFICATION -> "通知"
        TriggerConfig.TYPE_SMS -> "短信"
        TriggerConfig.TYPE_TIMER -> "定时"
        TriggerConfig.TYPE_CLIPBOARD -> "剪贴板"
        TriggerConfig.TYPE_KEY -> "按键"
        else -> type
    }

    fun icon(type: String?): ImageVector = when (type) {
        null, MANUAL -> Icons.Outlined.TouchApp
        TriggerConfig.TYPE_NOTIFICATION -> Icons.Outlined.NotificationsActive
        TriggerConfig.TYPE_SMS -> Icons.Outlined.Sms
        TriggerConfig.TYPE_TIMER -> Icons.Outlined.Schedule
        TriggerConfig.TYPE_CLIPBOARD -> Icons.Outlined.ContentPaste
        TriggerConfig.TYPE_KEY -> Icons.Outlined.Keyboard
        else -> Icons.Outlined.Bolt
    }
}
