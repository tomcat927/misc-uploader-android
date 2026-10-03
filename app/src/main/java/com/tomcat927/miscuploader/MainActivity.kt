package com.tomcat927.miscuploader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.tomcat927.miscuploader.data.UploadRepository
import com.tomcat927.miscuploader.ui.MainScreen
import com.tomcat927.miscuploader.ui.theme.MiscUploaderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var uploadRepository: UploadRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiscUploaderTheme {
                MainScreen()
            }
        }
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** 分享接收(M4 拍板):content:// 拷缓存入队,目标 = 仓库根目录;未连接时排队等连接 */
    private fun handleShareIntent(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))

            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()

            else -> emptyList()
        }.filterNotNull()
        if (uris.isEmpty()) return

        lifecycleScope.launch {
            val result = uploadRepository.enqueueFromShare(uris)
            Toast.makeText(
                this@MainActivity,
                if (result.count > 0) "已加入上传队列 ${result.count} 项 → ${result.target}" else "分享内容无法读取",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
