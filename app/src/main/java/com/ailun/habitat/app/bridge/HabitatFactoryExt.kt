package com.ailun.habitat.app.bridge

import android.content.Context
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.app.ai.LLMServiceProvider
import com.ailun.habitat.app.handlers.DynamicIslandNodeHandler
import com.ailun.habitat.app.handlers.MiniScreenNodeHandler
import com.ailun.habitat.app.handlers.NodeNotificationHandler
import com.ailun.habitat.app.handlers.OcrNodeHandler
import com.ailun.habitat.handlers.NodeLLMHandler
import com.ailun.habitat.handlers.NodeReadNotificationsHandler

/** Node types registered (or overridden) by [applyAppHandlers]; keep in sync. Used for the offline MCP tool manifest. */
val APP_NODE_TYPES: Set<String> = setOf(
    NodeHandlerFactory.ACTION_SEND_NOTIFICATION,
    NodeHandlerFactory.ACTION_DYNAMIC_ISLAND,
    NodeHandlerFactory.ACTION_MINI_SCREEN,
    NodeHandlerFactory.ACTION_AI_CHAT,
    NodeHandlerFactory.ACTION_READ_NOTIFICATIONS,
    NodeHandlerFactory.ACTION_OCR,
)

/** Registers handlers that live in :app (Android-framework-dependent) plus the AI handler wired with the real LLM service. */
fun NodeHandlerFactory.applyAppHandlers(context: Context) {
    register(NodeHandlerFactory.ACTION_SEND_NOTIFICATION, NodeNotificationHandler())
    register(NodeHandlerFactory.ACTION_DYNAMIC_ISLAND, DynamicIslandNodeHandler())
    register(NodeHandlerFactory.ACTION_MINI_SCREEN, MiniScreenNodeHandler())
    register(NodeHandlerFactory.ACTION_AI_CHAT, NodeLLMHandler(LLMServiceProvider.get(context)))
    register(NodeHandlerFactory.ACTION_READ_NOTIFICATIONS, NodeReadNotificationsHandler(AppNotificationSource))
    register(NodeHandlerFactory.ACTION_OCR, OcrNodeHandler())
}
