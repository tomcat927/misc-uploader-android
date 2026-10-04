package com.tomcat927.miscuploader.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tomcat927.miscuploader.BuildConfig

/** 应用内热更新(拍板 2026-10-04):检查 → 下载(进度) → SHA-256 校验 → 拉起系统安装器 */
@Composable
fun UpdateCard(viewModel: UpdateViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val startupCheck by viewModel.startupCheckEnabled.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("应用更新", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "v" + BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = viewModel::check,
                    enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                ) {
                    Text(if (state is UpdateState.Checking) "检查中…" else "检查更新")
                }
            }

            when (val s = state) {
                is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("检查中…", style = MaterialTheme.typography.bodySmall)
                }

                is UpdateState.Available -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "发现新版本 ${s.info.tagName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    s.info.releaseNotes?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = viewModel::download, modifier = Modifier.fillMaxWidth()) {
                        Text("下载并安装")
                    }
                }

                is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                    Text(
                        "下载中 ${(s.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is UpdateState.ReadyToInstall -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "下载完成，点击安装（${s.tagName}）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(onClick = viewModel::install, modifier = Modifier.fillMaxWidth()) {
                        Text("安装")
                    }
                }

                is UpdateState.Error -> Text(
                    "更新失败：${s.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                UpdateState.NoUpdate -> Text(
                    "已是最新版本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                UpdateState.Idle -> Text(
                    "检查并安装新版本（经 gh-proxy 加速，SHA-256 校验）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 启动检查更新开关(拍板 2026-10-04:默认开;静默,失败不打扰)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启动时检查更新", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "打开应用后自动检查，发现新版本会提示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = startupCheck,
                    onCheckedChange = viewModel::setStartupCheckEnabled,
                )
            }
        }
    }
}
