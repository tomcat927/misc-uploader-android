package com.tomcat927.miscuploader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tomcat927.miscuploader.ui.home.HomeScreen
import com.tomcat927.miscuploader.ui.queue.QueueScreen
import com.tomcat927.miscuploader.ui.settings.SettingsScreen
import com.tomcat927.miscuploader.ui.update.UpdateState
import com.tomcat927.miscuploader.ui.update.UpdateViewModel

/**
 * 底部三 tab(拍板:状态切换,不上 navigation-compose;各页状态在 ViewModel,切 tab 不丢数据)。
 */
private enum class MainTab(val label: String) {
    FILES("文件"),
    QUEUE("队列"),
    SETTINGS("设置"),
}

@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableStateOf(MainTab.FILES) }
    val snackbarHostState = remember { SnackbarHostState() }
    val updateViewModel: UpdateViewModel = viewModel()
    val updateState by updateViewModel.state.collectAsState()
    val showInstallConfirm by updateViewModel.showInstallConfirm.collectAsState()

    // 启动检查发现新版:未「暂不」过该版本 → 弹确认框;否则降级 Snackbar
    LaunchedEffect(Unit) {
        updateViewModel.foundEvents.collect { updateViewModel.onStartupUpdateFound(it) }
    }
    LaunchedEffect(Unit) {
        updateViewModel.snackEvents.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    MainTab.FILES -> Icons.Filled.Folder
                                    MainTab.QUEUE -> Icons.Filled.UploadFile
                                    MainTab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                MainTab.FILES -> HomeScreen()
                MainTab.QUEUE -> QueueScreen()
                MainTab.SETTINGS -> SettingsScreen()
            }
        }
    }

    // 发现新版本弹窗(主动检查/启动发现共用,任意 tab 可见):
    // Available → 确认是否下载;确认后同弹窗转进度条(可「后台下载」收起),完成自动拉安装器
    if (showInstallConfirm) {
        when (val s = updateState) {
            is UpdateState.Available -> AlertDialog(
                onDismissRequest = updateViewModel::dismissInstallConfirm,
                title = { Text("发现新版本") },
                text = {
                    Text("最新版本 ${s.info.tagName}，是否下载安装？" + (s.info.releaseNotes?.let { "\n\n$it" } ?: ""))
                },
                confirmButton = {
                    TextButton(onClick = updateViewModel::confirmInstall) { Text("下载并安装") }
                },
                dismissButton = {
                    TextButton(onClick = updateViewModel::dismissInstallConfirm) { Text("暂不") }
                },
            )

            is UpdateState.Downloading -> AlertDialog(
                onDismissRequest = updateViewModel::hideInstallProgress,
                title = { Text("正在下载更新") },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "已完成 ${(s.progress * 100).toInt()}%，完成后自动弹出安装",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = updateViewModel::hideInstallProgress) { Text("后台下载") }
                },
            )

            else -> Unit
        }
    }
}
