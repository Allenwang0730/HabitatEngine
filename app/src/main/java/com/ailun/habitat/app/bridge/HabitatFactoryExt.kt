package com.ailun.habitat.app.bridge

import android.content.Context
import com.ailun.habitat.NodeHandlerFactory
import com.ailun.habitat.app.ai.LLMServiceProvider
import com.ailun.habitat.app.handlers.DynamicIslandNodeHandler
import com.ailun.habitat.app.handlers.MiniScreenNodeHandler
import com.ailun.habitat.app.handlers.NodeNotificationHandler
import com.ailun.habitat.handlers.NodeLLMHandler
import com.ailun.habitat.handlers.NodeReadNotificationsHandler

/** Registers handlers that live in :app (Android-framework-dependent) plus the AI handler wired with the real LLM service. */
fun NodeHandlerFactory.applyAppHandlers(context: Context) {
    register(NodeHandlerFactory.ACTION_SEND_NOTIFICATION, NodeNotificationHandler())
    register(NodeHandlerFactory.ACTION_DYNAMIC_ISLAND, DynamicIslandNodeHandler())
    register(NodeHandlerFactory.ACTION_MINI_SCREEN, MiniScreenNodeHandler())
    register(NodeHandlerFactory.ACTION_AI_CHAT, NodeLLMHandler(LLMServiceProvider.get(context)))
    register(NodeHandlerFactory.ACTION_READ_NOTIFICATIONS, NodeReadNotificationsHandler(AppNotificationSource))
}
