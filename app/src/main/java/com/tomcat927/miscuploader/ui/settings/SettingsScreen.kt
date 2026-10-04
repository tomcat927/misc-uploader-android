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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tomcat927.miscuploader.data.SettingsRepository
import com.tomcat927.miscuploader.data.UploadMode

/**
 * 设置页(拍板对齐桌面端:无保存按钮,失焦即落盘;密码掩码回显 +「显示」;「连接」= 保存+测试一步)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val uploadMode by viewModel.uploadMode.collectAsState()
    val touchFocus by viewModel.touchFocus.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()
    val uploadConcurrency by viewModel.uploadConcurrency.collectAsState()
    val uploadMaxRetries by viewModel.uploadMaxRetries.collectAsState()
    val wifiOnly by viewModel.wifiOnly.collectAsState()

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
                    supportingText = {
                        Column {
                            Text("形如 https://host:5245（OpenList；内网可输 http://内网IP:5244）")
                            if (state.url.trim().lowercase().startsWith("http://")) {
                                Text(
                                    "明文连接：凭据与内容不经加密，仅建议可信内网使用",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
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
                // 掩码值回显(2026-10-04 修订):已存密码以 •••••••• 作为字段值;聚焦全选,输入即替换
                var pf by remember(state.passwordInput) {
                    mutableStateOf(
                        TextFieldValue(state.passwordInput, TextRange(state.passwordInput.length)),
                    )
                }
                OutlinedTextField(
                    value = pf,
                    onValueChange = { pf = it; viewModel.onPasswordChange(it.text) },
                    label = { Text("密码") },
                    placeholder = {
                        Text(if (state.hasStoredPassword) "留空沿用已保存密码" else "输入密码")
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { st ->
                            if (st.isFocused && pf.text == SettingsRepository.PASSWORD_MASK) {
                                pf = pf.copy(selection = TextRange(0, pf.text.length))
                            }
                        },
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

            com.tomcat927.miscuploader.ui.update.UpdateCard()

            TouchFocusCard(
                enabled = touchFocus,
                onToggle = viewModel::setTouchFocus,
            )

            ShowHiddenCard(
                enabled = showHidden,
                onToggle = viewModel::setShowHidden,
            )

            CommitOnFocusLost(viewModel::commitPigallery) {
                OutlinedTextField(
                    value = state.pigalleryBase,
                    onValueChange = viewModel::onPigalleryChange,
                    label = { Text("PiGallery2 地址（可选）") },
                    supportingText = { Text("填写后「文件」页远程栏出现相册按钮，点按在浏览器打开当前目录对应相册") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            UploadModeCard(
                mode = uploadMode,
                onSelect = viewModel::setUploadMode,
            )

            UploadSettingsCard(
                concurrency = uploadConcurrency,
                maxRetries = uploadMaxRetries,
                wifiOnly = wifiOnly,
                defaultShareDir = state.defaultShareDir,
                onConcurrency = viewModel::setUploadConcurrency,
                onMaxRetries = viewModel::setUploadMaxRetries,
                onWifiOnly = viewModel::setWifiOnly,
                onShareDirChange = viewModel::onShareDirChange,
                onShareDirCommit = viewModel::commitShareDir,
            )

            DiagnosticsCard(
                state = state.diagnostics,
                onToggle = viewModel::toggleDiagnostics,
                onRefresh = viewModel::loadDiagnostics,
            )
        }
    }
}

/** 上传设置(拍板 2026-10-04:并发/最大重试/仅 Wi-Fi/默认分享目录) */
@Composable
private fun UploadSettingsCard(
    concurrency: Int,
    maxRetries: Int,
    wifiOnly: Boolean,
    defaultShareDir: String,
    onConcurrency: (Int) -> Unit,
    onMaxRetries: (Int) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onShareDirChange: (String) -> Unit,
    onShareDirCommit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("上传设置", style = MaterialTheme.typography.titleSmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("并发数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(76.dp))
                listOf(1, 2, 3, 4).forEach { n ->
                    FilterChip(selected = concurrency == n, onClick = { onConcurrency(n) }, label = { Text("$n") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("最大重试", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(76.dp))
                listOf(0, 1, 2, 3, 5).forEach { n ->
                    FilterChip(selected = maxRetries == n, onClick = { onMaxRetries(n) }, label = { Text("$n") })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("仅 Wi-Fi 上传", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "开启后蜂窝网络下队列暂停，连上 Wi-Fi 自动续跑",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = wifiOnly, onCheckedChange = onWifiOnly)
            }

            CommitOnFocusLost(onShareDirCommit) {
                OutlinedTextField(
                    value = defaultShareDir,
                    onValueChange = onShareDirChange,
                    label = { Text("分享接收目标目录") },
                    supportingText = { Text("手动模式下系统分享进来的文件落此目录；自动归类模式仍按日期归 auto/年/月") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                "并发数与最大重试在下次队列启动时生效",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 双栏触摸聚焦开关(拍板 2026-10-04:默认开;关闭=两栏恒单列,点击只变高亮不改布局) */
@Composable
private fun TouchFocusCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("双栏触摸聚焦", style = MaterialTheme.typography.titleSmall)
                Text(
                    "开启：点哪栏哪栏变单列，另一栏两列总览；关闭：两栏均为单列，点击只变高亮",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/** 显示隐藏文件开关(拍板 2026-10-04:默认关;"." 前缀=隐藏,本地/远程同规则,切换即时生效) */
@Composable
private fun ShowHiddenCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("显示隐藏文件", style = MaterialTheme.typography.titleSmall)
                Text(
                    "显示以「.」开头的文件/文件夹（本地与远程同规则），切换即时生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/** 上传模式(拍板 A2,对齐桌面端两种模式) */
@Composable
private fun UploadModeCard(mode: UploadMode, onSelect: (UploadMode) -> Unit) {    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("上传模式", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == UploadMode.MANUAL,
                    onClick = { onSelect(UploadMode.MANUAL) },
                    label = { Text(UploadMode.MANUAL.label) },
                )
                FilterChip(
                    selected = mode == UploadMode.AUTO_DATE,
                    onClick = { onSelect(UploadMode.AUTO_DATE) },
                    label = { Text(UploadMode.AUTO_DATE.label) },
                )
            }
            Text(
                when (mode) {
                    UploadMode.MANUAL -> "上传到「文件」页右侧当前所在目录"
                    UploadMode.AUTO_DATE -> "按每个文件的修改时间自动归入 auto/年/月（分享接收同样生效）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
