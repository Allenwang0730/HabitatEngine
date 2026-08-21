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

private const val TAG = "LoginScreen"

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

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("登录") },
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
                    label = { Text("用户名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                )

                TextButton(
                    onClick = {
                        errorMessage = null
                        onDismiss()
                        onNavigateToRegister()
                    },
                    modifier = Modifier.align(Alignment.End),
                    enabled = !isLoading,
                ) {
                    Text("没有账号？注册", fontSize = 13.sp)
                }

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
                    val pass = password.trim()
                    if (user.isEmpty() || pass.isEmpty()) {
                        errorMessage = "用户名和密码不能为空"
                        return@TextButton
                    }
                    isLoading = true
                    errorMessage = null
                    scope.launch {
                        try {
                            HabitatApiClient.init(ProfilePrefs.getServerUrl(context))
                            val result = withContext(Dispatchers.IO) {
                                HabitatApiClient.login(user, pass)
                            }
                            result.fold(
                                onSuccess = { auth ->
                                    Log.d(TAG, "Login success: ${auth.username}")
                                    AuthStateManager.setAuth(auth.token, auth.userId, auth.username)
                                    isLoading = false
                                    onDismiss()
                                    onSuccess()
                                },
                                onFailure = { e ->
                                    Log.e(TAG, "Login failed", e)
                                    isLoading = false
                                    errorMessage = when (e) {
                                        is ApiException -> e.message
                                        else -> e.message ?: "网络连接失败"
                                    }
                                },
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Login error", e)
                            isLoading = false
                            errorMessage = e.message ?: "未知错误"
                        }
                    }
                },
                enabled = !isLoading,
            ) { Text("登录") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
            ) { Text("取消") }
        },
    )
}
