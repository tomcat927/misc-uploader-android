package com.tomcat927.miscuploader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tomcat927.miscuploader.ui.settings.SettingsScreen
import com.tomcat927.miscuploader.ui.theme.MiscUploaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiscUploaderTheme {
                // M1:只有设置页;M2 起双栏为首页,设置入口收进导航
                SettingsScreen()
            }
        }
    }
}
