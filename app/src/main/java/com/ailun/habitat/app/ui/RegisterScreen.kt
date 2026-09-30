package com.ailun.habitat.app.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.ailun.habitat.app.Spacing
import com.ailun.habitat.app.auth.AuthStateManager
import com.ailun.habitat.app.network.ApiException
import com.ailun.habitat.app.network.HabitatApiClient
import com.ailun.habitat.app.ui.components.SecretField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "RegisterScreen"

@Composable
fun RegisterDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val mismatch = confirmPassword.isNotEmpty() && confirmPassword != password

    fun submit() {
        val user = username.trim()
        val mail = email.trim()
        val pass = password.trim()
        val confirm = confirmPassword.trim()
        errorMessage = when {
            user.length !in 3..50 -> "用户名需要 3-50 个字符"
            !android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches() -> "请输入有效的邮箱地址"
            pass.length < 6 -> "密码至少需要 6 个字符"
            pass != confirm -> "两次密码不一致"
            else -> null
        }
        if (errorMessage != null) return

        isLoading = true
        scope.launch {
            try {
                HabitatApiClient.init(ProfilePrefs.getServerUrl(context))
                val result = withContext(Dispatchers.IO) { HabitatApiClient.register(user, mail, pass) }
                result.fold(
                    onSuccess = { auth ->
                        AuthStateManager.setAuth(auth.token, auth.userId, auth.username)
                        isLoading = false
                        onDismiss()
                        onSuccess()
                    },
                    onFailure = { e ->
                        Log.w(TAG, "Register failed: ${e.message}")
                        isLoading = false
                        errorMessage = when (e) {
                            is ApiException -> e.message
                            else -> e.message ?: "网络连接失败"
                        }
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "Register error: ${e.message}")
                isLoading = false
                errorMessage = e.message ?: "未知错误"
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        icon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
        title = { Text("注册") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                FormError(errorMessage)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; errorMessage = null },
                    label = { Text("用户名") },
                    supportingText = { Text("3-50 个字符") },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; errorMessage = null },
                    label = { Text("邮箱") },
                    leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                )
                SecretField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = "密码",
                    supportingText = "至少 6 个字符",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    showPaste = false,
                    monospace = false,
                    leadingIcon = Icons.Outlined.Lock,
                    imeAction = ImeAction.Next,
                )
                SecretField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; errorMessage = null },
                    label = "确认密码",
                    isError = mismatch,
                    supportingText = if (mismatch) "两次密码不一致" else null,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    showPaste = false,
                    monospace = false,
                    leadingIcon = Icons.Outlined.Lock,
                    onImeAction = ::submit,
                )
            }
        },
        confirmButton = { SubmitButton("注册", isLoading, onClick = ::submit) },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isLoading) { Text("取消") } },
    )
}
