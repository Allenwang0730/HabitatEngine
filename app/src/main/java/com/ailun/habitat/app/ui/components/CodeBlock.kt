package com.ailun.habitat.app.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ailun.habitat.app.HabitatTextStyles
import com.ailun.habitat.app.HabitatTheme
import com.ailun.habitat.app.Spacing

/** 复制到剪贴板。sensitive=true 时在 Android 13+ 标记为敏感内容（不在剪贴板预览中显示）。 */
fun copyToClipboard(context: Context, text: String, label: String = "Habitat", sensitive: Boolean = false, toast: String? = "已复制") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    cm.setPrimaryClip(clip)
    // Android 13+ 系统自带复制提示，避免重复
    if (toast != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }
}

fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

/** 带复制按钮的等宽代码块（可选择、可横向滚动）。 */
@Composable
fun CodeBlock(
    code: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    maxHeight: Dp = 320.dp,
    copyLabel: String = "复制代码",
) {
    val context = LocalContext.current
    val ext = HabitatTheme.extendedColors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = ext.codeBackground,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { copyToClipboard(context, code) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = copyLabel, modifier = Modifier.size(18.dp))
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
            ) {
                SelectionContainer {
                    Text(code, style = HabitatTextStyles.code, color = ext.codeForeground, softWrap = false)
                }
            }
        }
    }
}
