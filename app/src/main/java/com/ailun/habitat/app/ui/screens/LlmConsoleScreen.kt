package com.ailun.habitat.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ailun.habitat.app.HabitatTextStyles
import com.ailun.habitat.app.HabitatTheme
import com.ailun.habitat.app.Spacing
import com.ailun.habitat.app.tabular
import com.ailun.habitat.app.ui.components.IconTile
import com.ailun.habitat.app.ui.components.PillStatus
import com.ailun.habitat.app.ui.components.SectionHeader
import com.ailun.habitat.app.ui.components.StatusPill
import com.ailun.habitat.app.ui.viewmodel.LlmConsoleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmConsoleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = remember {
        ViewModelProvider(context as androidx.activity.ComponentActivity)[LlmConsoleViewModel::class.java]
    }
    var prompt by rememberSaveable { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importModel(uri)
    }

    LaunchedEffect(vm.message) {
        vm.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.message = null }
    }

    BackHandler(onBack = onBack)
    LifecycleResumeEffect(Unit) {
        vm.resumeTick++
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LLM 控制台") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Outlined.Memory, size = 40)
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text("端侧模型", style = MaterialTheme.typography.titleMedium)
                            Text("LiteRT-LM · .litertlm 文件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when {
                            vm.loading -> StatusPill(PillStatus.Running, "加载中")
                            vm.ready -> StatusPill(PillStatus.Success, "已就绪")
                            else -> StatusPill(PillStatus.Idle, "未加载")
                        }
                    }

                    OutlinedTextField(
                        value = vm.modelPath,
                        onValueChange = { vm.modelPath = it },
                        label = { Text("模型路径") },
                        textStyle = HabitatTextStyles.codeSmall,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text(if (vm.isPrivatePath) "位于应用私有目录，无需额外权限" else "外部路径需要「所有文件访问」权限；推荐用下方按钮导入")
                        },
                    )

                    val progress = vm.copyProgress
                    if (progress != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "正在导入 ${vm.copyingName ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium.tabular())
                                TextButton(onClick = vm::cancelImport) { Text("取消") }
                            }
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        }
                    } else {
                        OutlinedButton(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.sm))
                            Text("选择模型文件并导入")
                        }
                    }

                    if (vm.needsStoragePermission) {
                        Surface(shape = MaterialTheme.shapes.medium, color = HabitatTheme.extendedColors.warningContainer) {
                            Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.WarningAmber, null, tint = HabitatTheme.extendedColors.onWarningContainer)
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    "读取外部路径需要授予文件访问权限",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = HabitatTheme.extendedColors.onWarningContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                            .setData(Uri.parse("package:${context.packageName}")),
                                    )
                                }) { Text("去授权") }
                            }
                        }
                    }

                    Button(
                        onClick = vm::loadModel,
                        enabled = !vm.loading && vm.copyProgress == null && !vm.needsStoragePermission && vm.modelPath.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(if (vm.ready) "重新加载" else "加载模型")
                    }
                }
            }

            SectionHeader("推理测试")
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("输入 Prompt") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = vm.ready,
                    )
                    FilledTonalButton(
                        onClick = { vm.send(prompt) },
                        enabled = vm.ready && !vm.chatting && prompt.isNotBlank(),
                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(if (vm.chatting) "生成中…" else "发送")
                    }
                    if (vm.chatting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (vm.response.isNotEmpty()) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            SelectionContainer {
                                Text(vm.response, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(Spacing.md))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.padding(bottom = Spacing.xl))
        }
    }
}
