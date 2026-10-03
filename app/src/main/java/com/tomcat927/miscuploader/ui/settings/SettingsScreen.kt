package com.tomcat927.miscuploader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 设置页(拍板对齐桌面端:无保存按钮,失焦即落盘;密码掩码回显 +「显示」;「连接」= 保存+测试一步)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CommitOnFocusLost(viewModel::commitFields) {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = viewModel::onUrlChange,
                    label = { Text("服务器地址") },
                    supportingText = { Text("形如 https://host:5245（OpenList，需 HTTPS）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CommitOnFocusLost(viewModel::commitFields) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CommitOnFocusLost(viewModel::commitFields) {
                OutlinedTextField(
                    value = state.passwordInput,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text("密码") },
                    placeholder = {
                        if (state.hasStoredPassword && !state.revealing) {
                            Text("••••••••（已保存，留空沿用）")
                        } else {
                            Text("输入密码")
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (state.revealing) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = viewModel::toggleReveal) {
                            Text(if (state.revealing) "隐藏" else "显示")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.validationHint?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = viewModel::connect,
                enabled = !state.connecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.connecting) "连接中…" else "连接")
            }

            ConnectionStatusCard(state)

            DiagnosticsCard(
                state = state.diagnostics,
                onToggle = viewModel::toggleDiagnostics,
                onRefresh = viewModel::loadDiagnostics,
            )
        }
    }
}

/**
 * 失焦即提交(拍板:无保存按钮)。
 * 焦点跟踪挂在包裹的 FocusGroup 上:组内任一字段获得过焦点又整体失去时提交一次。
 */
@Composable
private fun CommitOnFocusLost(onCommit: () -> Unit, content: @Composable () -> Unit) {
    var hadFocus by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.onFocusChanged { state ->
            if (hadFocus && !state.hasFocus) onCommit()
            hadFocus = state.hasFocus
        },
    ) {
        content()
    }
}

@Composable
private fun ConnectionStatusCard(state: SettingsUiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                state.connected -> MaterialTheme.colorScheme.primaryContainer
                state.connectionMessage != null -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.connecting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(
                    text = when {
                        state.connected -> "已连接 · 根目录 ${state.connectedRootCount ?: 0} 项"
                        state.connecting -> "连接中…"
                        state.connectionMessage != null -> "连接失败：${state.connectionMessage}"
                        else -> "未连接"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.connected) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "远程浏览在 M2 接入；上传队列在 M3 接入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
