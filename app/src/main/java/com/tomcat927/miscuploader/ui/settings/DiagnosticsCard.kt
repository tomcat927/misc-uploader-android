package com.tomcat927.miscuploader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 诊断卡(M4 拍板:诊断能力下沉——上传日志尾部 + 应用历史退出原因,可复制回传)。
 */
@Composable
fun DiagnosticsCard(
    state: DiagnosticsUiState,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("诊断", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onToggle) { Text(if (state.expanded) "收起" else "查看") }
            }

            if (!state.expanded) {
                Text(
                    "上传日志与系统退出原因（排查后台/前台服务问题用）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新") }
                    TextButton(
                        onClick = {
                            clipboard.setText(
                                AnnotatedString("=== 上传日志 ===\n${state.logText}\n=== 退出原因 ===\n${state.exitText}"),
                            )
                        },
                        enabled = state.logText.isNotEmpty(),
                    ) {
                        Text("复制全部")
                    }
                    if (state.loading) {
                        Spacer(Modifier.weight(1f))
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).heightIn(max = 18.dp))
                    }
                }

                Text("上传日志（尾部）", style = MaterialTheme.typography.labelMedium)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(
                        state.logText.ifEmpty { "（空）" },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Text("最近退出原因（Android 11+）", style = MaterialTheme.typography.labelMedium)
                Text(
                    state.exitText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
