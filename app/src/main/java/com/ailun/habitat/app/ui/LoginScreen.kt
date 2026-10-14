package com.ailun.habitat.app.ui

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ailun.habitat.app.Spacing
import com.ailun.habitat.app.auth.AuthStateManager
import com.ailun.habitat.app.network.ApiException
import com.ailun.habitat.app.network.HabitatApiClient
import com.ailun.habitat.app.ui.components.SecretField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LoginScreen"

/** 表单内联错误（带 liveRegion，读屏会播报）。 */
@Composable
internal fun FormError(message: String?) {
    AnimatedVisibility(message != null) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/** 提交按钮：提交中显示进度并禁用。 */
@Composable
internal fun SubmitButton(label: String, loading: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !loading) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(label)
    }
}

@Composable
fun LoginDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    fun submit() {
        val user = username.trim()
        val pass = password.trim()
        if (user.isEmpty() || pass.isEmpty()) {
            errorMessage = "用户名和密码不能为空"
            return
        }
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                HabitatApiClient.init(ProfilePrefs.getServerUrl(context))
                val result = withContext(Dispatchers.IO) { HabitatApiClient.login(user, pass) }
                result.fold(
                    onSuccess = { auth ->
                        AuthStateManager.setAuth(auth.token, auth.userId, auth.username)
                        isLoading = false
                        onDismiss()
                        onSuccess()
                    },
                    onFailure = { e ->
                        Log.w(TAG, "Login failed: ${e.message}")
                        isLoading = false
                        errorMessage = when (e) {
                            is ApiException -> e.message
                            else -> e.message ?: "网络连接失败"
                        }
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "Login error: ${e.message}")
                isLoading = false
                errorMessage = e.message ?: "未知错误"
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        icon = { Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null) },
        title = { Text("登录") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                FormError(errorMessage)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; errorMessage = null },
                    label = { Text("用户名") },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                )
                SecretField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = "密码",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    showPaste = false,
                    monospace = false,
                    leadingIcon = Icons.Outlined.Lock,
                    onImeAction = ::submit,
                )
                TextButton(
                    onClick = { errorMessage = null; onDismiss(); onNavigateToRegister() },
                    modifier = Modifier.align(Alignment.End),
                    enabled = !isLoading,
                ) { Text("没有账号？注册") }
            }
        },
        confirmButton = { SubmitButton("登录", isLoading, onClick = ::submit) },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isLoading) { Text("取消") } },
    )
}
