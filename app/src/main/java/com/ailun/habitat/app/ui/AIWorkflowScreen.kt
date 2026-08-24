package com.ailun.habitat.app.ui

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ailun.habitat.HabitatWorkflow
import com.ailun.habitat.WorkflowRepository
import com.ailun.habitat.app.ai.GeneratedWorkflow
import com.ailun.habitat.app.ai.WorkflowAIClient
import com.ailun.habitat.app.ai.WorkflowAIGenerator
import kotlinx.coroutines.launch
import java.util.UUID

/** 一条聊天消息。 */
data class ChatMsg(
    val isUser: Boolean,
    val text: String,
    val generated: GeneratedWorkflow? = null,
    var saved: Boolean = false,
)

class AIWorkflowViewModel(app: Application) : AndroidViewModel(app) {
    val messages: SnapshotStateList<ChatMsg> = mutableStateListOf()
    val busy = mutableStateOf(false)

    /** 最近一次成功生成的工作流（多轮对话时作为"在现有基础上修改"的基线）。 */
    private var lastGenerated: GeneratedWorkflow? = null

    /** 会话记忆：记录 (用户需求 → 生成结果描述)，供下一轮生成连贯上下文。 */
    private val history = mutableListOf<Pair<String, String>>()

    fun send(request: String) {
        val req = request.trim()
        if (req.isEmpty() || busy.value) return
        messages.add(ChatMsg(isUser = true, text = req))
        busy.value = true
        viewModelScope.launch {
            val result = WorkflowAIGenerator.generate(
                getApplication(), req, lastGenerated?.workflowJson, history,
            )
            busy.value = false
            result.onSuccess { gen ->
                lastGenerated = gen
                history.add(req to gen.description)
                messages.add(ChatMsg(isUser = false, text = "", generated = gen))
            }.onFailure {
                messages.add(ChatMsg(isUser = false, text = "❌ 生成失败：${it.message}"))
            }
        }
    }

    /** 编辑后重新验证并保存（编辑态的 JSON 可能不合法）。 */
    fun saveEdited(msg: ChatMsg, editedJson: String): String? {
        val parsed = WorkflowAIGenerator.parseAndValidate(editedJson).getOrElse { return null }
        val gen = parsed
        val workflow = HabitatWorkflow(
            id = UUID.randomUUID().toString(),
            name = gen.name,
            description = gen.description,
            jsonContent = gen.workflowJson,
        )
        WorkflowRepository.upsert(getApplication(), workflow)
        // 用编辑后的结果替换消息（generated 是 val，需整体替换）
        val idx = messages.indexOf(msg)
        if (idx >= 0) {
            messages[idx] = msg.copy(generated = gen, saved = true)
        }
        lastGenerated = gen
        return workflow.id
    }

    fun save(msg: ChatMsg): String? {
        val gen = msg.generated ?: return null
        val workflow = HabitatWorkflow(
            id = UUID.randomUUID().toString(),
            name = gen.name,
            description = gen.description,
            jsonContent = gen.workflowJson,
        )
        WorkflowRepository.upsert(getApplication(), workflow)
        msg.saved = true
        lastGenerated = gen
        return workflow.id
    }

    /** 保存并挂载到悬浮窗。 */
    fun saveAndMount(msg: ChatMsg): String? {
        val id = save(msg) ?: return null
        WorkflowRepository.addFloatMountedWorkflowId(getApplication(), id)
        com.ailun.habitat.HabitatStateStore.notifyLibraryChanged()
        return id
    }
}

@Composable
fun AIWorkflowScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = remember { AIWorkflowViewModel(context.applicationContext as Application) }
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
            Text("AI 工作流生成", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "用自然语言描述需求，AI（DeepSeek）自动生成 Habitat 工作流并可保存到库。支持多轮对话记忆、编辑后保存、挂载悬浮窗。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // API Key 配置（不硬编码，用户在此输入保存）
        var apiKeyInput by remember {
            mutableStateOf(WorkflowAIClient.getApiKey(context) ?: "")
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("DeepSeek API Key") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                WorkflowAIClient.setApiKey(context, apiKeyInput)
                android.widget.Toast.makeText(context, "已保存 API Key", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("保存") }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.messages) { msg ->
                if (msg.isUser) {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "🧑 ${msg.text}",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else if (msg.generated != null) {
                    GeneratedCard(msg, vm, context)
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            msg.text,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (vm.busy.value) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("生成中…")
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                label = { Text("描述你的需求…") },
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (input.isNotBlank()) {
                    vm.send(input)
                    input = ""
                }
            }, enabled = !vm.busy.value) {
                Text("生成")
            }
        }
    }
}

@Composable
private fun GeneratedCard(msg: ChatMsg, vm: AIWorkflowViewModel, context: android.content.Context) {
    val gen = msg.generated ?: return
    var editing by remember { mutableStateOf(false) }
    var editJson by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🤖 ${gen.name}", style = MaterialTheme.typography.titleSmall)
            Text(gen.description, style = MaterialTheme.typography.bodySmall)

            if (editing) {
                Text("编辑 JSON（保存前会重新校验）", style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    value = editJson,
                    onValueChange = { editJson = it; editError = null },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                editError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text(
                    gen.workflowJson,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 8,
                )
            }

            Row {
                if (editing) {
                    TextButton(onClick = {
                        vm.saveEdited(msg, editJson)?.let {
                            Toast.makeText(context, "已保存编辑", Toast.LENGTH_SHORT).show()
                            editing = false
                        } ?: run { editError = "JSON 校验失败，请检查格式" }
                    }) { Text("保存编辑") }
                    TextButton(onClick = { editing = false }) { Text("取消") }
                } else {
                    TextButton(onClick = {
                        editing = true; editJson = gen.workflowJson
                    }) { Text("编辑") }
                    TextButton(onClick = {
                        vm.save(msg)?.let { Toast.makeText(context, "已保存到工作流库", Toast.LENGTH_SHORT).show() }
                    }) { Text("保存") }
                    TextButton(onClick = {
                        vm.saveAndMount(msg)?.let {
                            Toast.makeText(context, "已保存并挂载到悬浮窗", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("挂载悬浮窗") }
                }
            }
            if (msg.saved && !editing) {
                Text("✓ 已保存", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
