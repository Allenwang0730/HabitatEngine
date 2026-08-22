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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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

    fun send(request: String) {
        val req = request.trim()
        if (req.isEmpty() || busy.value) return
        messages.add(ChatMsg(isUser = true, text = req))
        busy.value = true
        viewModelScope.launch {
            val result = WorkflowAIGenerator.generate(getApplication(), req)
            busy.value = false
            result.onSuccess { gen ->
                messages.add(ChatMsg(isUser = false, text = "", generated = gen))
            }.onFailure {
                messages.add(ChatMsg(isUser = false, text = "❌ 生成失败：${it.message}"))
            }
        }
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
        return workflow.id
    }
}

@Composable
fun AIWorkflowScreen() {
    val context = LocalContext.current
    val vm = remember { AIWorkflowViewModel(context.applicationContext as Application) }
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("AI 工作流生成", style = MaterialTheme.typography.headlineSmall)
        Text(
            "用自然语言描述需求，AI（DeepSeek）自动生成 Habitat 工作流并可保存到库。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🤖 ${gen.name}", style = MaterialTheme.typography.titleSmall)
            Text(gen.description, style = MaterialTheme.typography.bodySmall)
            Text(
                gen.workflowJson,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 8,
            )
            Row {
                if (msg.saved) {
                    Text("✓ 已保存", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                } else {
                    TextButton(onClick = {
                        vm.save(msg)?.let {
                            Toast.makeText(context, "已保存到工作流库", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("保存") }
                }
            }
        }
    }
}
