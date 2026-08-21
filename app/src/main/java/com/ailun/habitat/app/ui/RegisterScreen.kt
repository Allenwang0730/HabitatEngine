package com.ailun.habitat.app.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ailun.habitat.app.auth.AuthStateManager
import com.ailun.habitat.app.network.ApiException
import com.ailun.habitat.app.network.HabitatApiClient
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

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("注册") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (errorMessage != null) {
                    Text(
                        errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; errorMessage = null },
                    label = { Text("用户名 (3-50字符)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; errorMessage = null },
                    label = { Text("邮箱") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = { Text("密码 (至少6字符)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                )

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; errorMessage = null },
                    label = { Text("确认密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                )

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val user = username.trim()
                    val mail = email.trim()
                    val pass = password.trim()
                    val confirm = confirmPassword.trim()

                    if (user.length < 3 || user.length > 50) {
                        errorMessage = "用户名需要 3-50 个字符"
                        return@TextButton
                    }
                    if (!android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches()) {
                        errorMessage = "请输入有效的邮箱地址"
                        return@TextButton
                    }
                    if (pass.length < 6) {
                        errorMessage = "密码至少需要 6 个字符"
                        return@TextButton
                    }
                    if (pass != confirm) {
                        errorMessage = "两次密码不一致"
                        return@TextButton
                    }

                    isLoading = true
                    errorMessage = null
                    scope.launch {
                        try {
                            HabitatApiClient.init(ProfilePrefs.getServerUrl(context))
                            val result = withContext(Dispatchers.IO) {
                                HabitatApiClient.register(user, mail, pass)
                            }
                            result.fold(
                                onSuccess = { auth ->
                                    Log.d(TAG, "Register success: ${auth.username}")
                                    AuthStateManager.setAuth(auth.token, auth.userId, auth.username)
                                    isLoading = false
                                    onDismiss()
                                    onSuccess()
                                },
                                onFailure = { e ->
                                    Log.e(TAG, "Register failed", e)
                                    isLoading = false
                                    errorMessage = when (e) {
                                        is ApiException -> e.message
                                        else -> e.message ?: "网络连接失败"
                                    }
                                },
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Register error", e)
                            isLoading = false
                            errorMessage = e.message ?: "未知错误"
                        }
                    }
                },
                enabled = !isLoading,
            ) { Text("注册") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
            ) { Text("取消") }
        },
    )
}
