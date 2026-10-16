package com.tomcat927.miscuploader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.tomcat927.miscuploader.data.SettingsRepository

/**
 * 「AI 助手」设置卡(拍板:OpenAI 兼容;默认关,只建议不执行)。
 * 配置 = 接口地址(含 /v1) + API Key(Keystore 加密,掩码回显) + 多模态模型(获取/手选/探针验证)。
 */
@Composable
internal fun AiAssistantCard(
    enabled: Boolean,
    baseUrl: String,
    apiKeyInput: String,
    hasApiKey: Boolean,
    revealing: Boolean,
    model: String,
    models: List<String>,
    loadingModels: Boolean,
    testing: Boolean,
    status: String?,
    visionOk: Boolean?,
    toolsTesting: Boolean,
    toolsOk: Boolean?,
    onEnabledChange: (Boolean) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onReveal: () -> Unit,
    onCommit: () -> Unit,
    onModelChange: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onFetchModels: () -> Unit,
    onVerifyVision: () -> Unit,
    onVerifyTools: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("AI 助手", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "接入 OpenAI 兼容接口，用多模态模型辅助判断文件该传到哪；默认关闭，只有在你启用后才会把内容发送给该接口",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            CommitOnFocusLost(onCommit) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("接口地址") },
                    supportingText = { Text("形如 https://host/v1（OpenAI 兼容；端点 /models 与 /chat/completions）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CommitOnFocusLost(onCommit) {
                var kf by remember(apiKeyInput) {
                    mutableStateOf(TextFieldValue(apiKeyInput, TextRange(apiKeyInput.length)))
                }
                OutlinedTextField(
                    value = kf,
                    onValueChange = { kf = it; onApiKeyChange(it.text) },
                    label = { Text("API Key") },
                    placeholder = { Text(if (hasApiKey) "留空沿用已保存密钥" else "输入 API Key") },
                    singleLine = true,
                    visualTransformation = if (revealing) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = onReveal) { Text(if (revealing) "隐藏" else "显示") }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { st ->
                            if (st.isFocused && kf.text == SettingsRepository.PASSWORD_MASK) {
                                kf = kf.copy(selection = TextRange(0, kf.text.length))
                            }
                        },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onFetchModels, enabled = !loadingModels) {
                    if (loadingModels) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("获取模型")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onVerifyVision, enabled = !testing && model.isNotBlank()) {
                    Text(if (testing) "验证中…" else "验证多模态")
                }
                Button(onClick = onVerifyTools, enabled = !toolsTesting && model.isNotBlank()) {
                    Text(if (toolsTesting) "验证中…" else "验证工具调用")
                }
            }

            ModelPicker(
                model = model,
                models = models,
                onModelChange = onModelChange,
                onSelectModel = onSelectModel,
                onCommit = onCommit,
            )

            status?.let { s ->
                Text(
                    s,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (visionOk ?: toolsOk) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** 模型选择:可手输,或点「获取模型」后从下拉里挑 */
@Composable
private fun ModelPicker(
    model: String,
    models: List<String>,
    onModelChange: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onCommit: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        CommitOnFocusLost(onCommit) {
            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                label = { Text("模型") },
                placeholder = { Text("点「获取模型」后选择，或手动输入模型名") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { expanded = true }, enabled = models.isNotEmpty()) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 56.dp),
        ) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m + if (m == model) " ✓" else "") },
                    onClick = {
                        onSelectModel(m)
                        expanded = false
                    },
                )
            }
        }
    }
}
