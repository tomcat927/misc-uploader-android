package com.tomcat927.miscuploader.ui.viewer

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.tomcat927.miscuploader.core.OpenListApiException
import java.io.File
import kotlin.math.roundToInt

/** 文件大类(拍板:图片/文本内置查看,其余委托系统;hex/zip/apk 不做) */
enum class FileKind(val label: String) {
    IMAGE("图片"),
    TEXT("文本"),
    OTHER("其他");

    companion object {
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val TEXT_EXT = setOf(
            "txt", "log", "md", "json", "xml", "yaml", "yml", "ini", "conf", "cfg", "properties",
            "csv", "html", "css", "js", "ts", "py", "kt", "java", "c", "cpp", "h", "sh", "bat", "sql",
        )

        fun of(name: String): FileKind {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext in IMAGE_EXT -> IMAGE
                ext in TEXT_EXT -> TEXT
                else -> OTHER
            }
        }
    }
}

data class ViewerRequest(
    val isLocal: Boolean,
    val kind: FileKind,
    /** 图片类 = 可翻看的图片条目;文本/其他 = 单元素 */
    val items: List<com.tomcat927.miscuploader.ui.home.FileItem>,
    val index: Int,
)

private const val TEXT_CHUNK = 256 * 1024
private const val REMOTE_PREVIEW_LIMIT = 50L * 1024 * 1024

fun openWithSystem(context: Context, file: File) {
    val ext = file.extension.lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * 文件查看对话框(拍板:图片全屏/缩放/同目录翻看;文本只读/编码识别/分块;其余委托系统打开;
 * 远程文件经 raw_url 下载到缓存,>50MB 文本不进查看器)。
 */
@Composable
fun FileViewerDialog(
    request: ViewerRequest,
    localFile: (com.tomcat927.miscuploader.ui.home.FileItem) -> File,
    remoteRawUrl: suspend (com.tomcat927.miscuploader.ui.home.FileItem) -> String,
    remoteDownload: suspend (com.tomcat927.miscuploader.ui.home.FileItem, onProgress: (Float) -> Unit) -> File,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val start = request.items.getOrNull(request.index) ?: request.items.firstOrNull() ?: return

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (request.kind) {
            FileKind.IMAGE -> ImagePager(request, localFile, remoteRawUrl)
            FileKind.TEXT -> TextViewer(request, localFile, remoteDownload)
            FileKind.OTHER -> DelegateViewer(request, localFile, remoteDownload)
        }

        // 顶部:标题 + 关闭
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    start.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (request.kind == FileKind.IMAGE && request.items.size > 1) {
                    Text(
                        "${request.index + 1}/${request.items.size} · 左右滑动翻看",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            FilledTonalClose(onClose)
        }
    }
}

@Composable
private fun FilledTonalClose(onClose: () -> Unit) {
    androidx.compose.material3.FilledTonalIconButton(
        onClick = onClose,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

// ---- 图片 ----

@Composable
private fun ImagePager(
    request: ViewerRequest,
    localFile: (com.tomcat927.miscuploader.ui.home.FileItem) -> File,
    remoteRawUrl: suspend (com.tomcat927.miscuploader.ui.home.FileItem) -> String,
) {
    val pagerState = rememberPagerState(initialPage = request.index) { request.items.size }
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val item = request.items[page]
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var url by remember(item.name) { mutableStateOf<String?>(null) }
        var failed by remember(item.name) { mutableStateOf(false) }

        if (!request.isLocal && url == null && !failed) {
            LaunchedEffect(item.name) {
                try {
                    url = remoteRawUrl(item)
                } catch (e: Exception) {
                    failed = true
                }
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                request.isLocal -> ZoomableImage(
                    model = localFile(item),
                    scale = scale,
                    onScale = { scale = it },
                    offset = offset,
                    onOffset = { offset = it },
                )

                failed -> Text("加载失败", color = Color.White.copy(alpha = 0.7f))
                url == null -> CircularProgressIndicator(color = Color.White)
                else -> ZoomableImage(
                    model = url,
                    scale = scale,
                    onScale = { scale = it },
                    offset = offset,
                    onOffset = { offset = it },
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(
    model: Any,
    scale: Float,
    onScale: (Float) -> Unit,
    offset: androidx.compose.ui.geometry.Offset,
    onOffset: (androidx.compose.ui.geometry.Offset) -> Unit,
) {
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                    onScale(newScale)
                    onOffset(if (newScale > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero)
                }
            },
    )
}

// ---- 文本(只读,分块) ----

@Composable
private fun TextViewer(
    request: ViewerRequest,
    localFile: (com.tomcat927.miscuploader.ui.home.FileItem) -> File,
    remoteDownload: suspend (com.tomcat927.miscuploader.ui.home.FileItem, onProgress: (Float) -> Unit) -> File,
) {
    val item = request.items.first()
    var text by remember { mutableStateOf("加载中…") }
    var loading by remember { mutableStateOf(true) }
    var hasMore by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<File?>(null) }
    var nextOffset by remember { mutableLongStateOf(0L) }
    var charset by remember { mutableStateOf("UTF-8") }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(-1f) }

    fun readChunk(f: File, offset: Long): Pair<String, Long> {
        val bytes = f.inputStream().use { input ->
            input.skip(offset)
            val buf = ByteArray(TEXT_CHUNK)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
            }
            buf.copyOf(n)
        }
        if (offset == 0L) {
            val detector = org.mozilla.universalchardet.UniversalDetector(null)
            detector.handleData(bytes)
            detector.dataEnd()
            detector.detectedCharset?.let { charset = it }
        }
        val decoded = String(bytes, charset(charset))
        return decoded to offset + bytes.size
    }

    LaunchedEffect(item.name) {
        try {
            val f: File = if (request.isLocal) {
                localFile(item)
            } else {
                val info = remoteDownload(item) { p -> downloadProgress = p }
                info
            }.also { file = it }
            if (f.length() > REMOTE_PREVIEW_LIMIT) {
                error = "文件超过 50MB，不进文本查看器"
            } else {
                val (chunk, next) = readChunk(f, 0)
                text = chunk.ifEmpty { "（空文件）" }
                hasMore = next < f.length()
                nextOffset = next
                error = null
            }
            loading = false
        } catch (e: Exception) {
            loading = false
            error = e.message ?: e.javaClass.simpleName
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading) {
                if (downloadProgress >= 0) {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "下载预览 ${(downloadProgress * 100).toInt()}%",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    SelectionContainer {
                        Text(
                            text + if (hasMore) "\n\n…（还有更多内容）" else "",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }
        if (hasMore && !loading) {
            TextButton(
                onClick = {
                    val f = file ?: return@TextButton
                    loading = true
                    try {
                        val (chunk, next) = readChunk(f, nextOffset)
                        text += chunk
                        nextOffset = next
                        hasMore = next < f.length()
                    } catch (e: Exception) {
                        error = e.message ?: "读取失败"
                    }
                    loading = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("加载更多")
            }
        }
    }
}

// ---- 其他类型:委托系统打开 ----

@Composable
private fun DelegateViewer(
    request: ViewerRequest,
    localFile: (com.tomcat927.miscuploader.ui.home.FileItem) -> File,
    remoteDownload: suspend (com.tomcat927.miscuploader.ui.home.FileItem, onProgress: (Float) -> Unit) -> File,
) {
    val item = request.items.first()
    val context = LocalContext.current
    var progress by remember { mutableFloatStateOf(-1f) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.name) {
        try {
            val f: File = if (request.isLocal) {
                localFile(item)
            } else {
                remoteDownload(item) { p -> progress = p }
            }
            openWithSystem(context, f)
        } catch (e: OpenListApiException) {
            error = e.message ?: "打开失败"
        } catch (e: Exception) {
            error = "打开失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            error != null -> Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )

            progress >= 0 -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Text(
                    "下载中 ${(progress * 100).toInt()}%（完成后用系统应用打开）",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}
