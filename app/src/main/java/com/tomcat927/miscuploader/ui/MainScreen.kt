package com.tomcat927.miscuploader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tomcat927.miscuploader.ui.home.HomeScreen
import com.tomcat927.miscuploader.ui.queue.QueueScreen
import com.tomcat927.miscuploader.ui.settings.SettingsScreen

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

    Scaffold(
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
}
