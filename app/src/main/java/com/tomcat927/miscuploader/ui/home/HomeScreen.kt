package com.tomcat927.miscuploader.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 双栏主界面(拍板借 SplitLanzou:触摸聚焦、聚焦侧单列/非聚焦侧两列、400ms 展开动画 + 边缘把手)。
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val left by viewModel.left.collectAsState()
    val right by viewModel.right.collectAsState()
    val focused by viewModel.focusedSide.collectAsState()
    val expanded by viewModel.expandedSide.collectAsState()
    val storageGranted by viewModel.storageGranted.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var mkdirSide by remember { mutableStateOf<Side?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    val leftWeight by animateFloatAsState(
        targetValue = when (expanded) {
            Side.LEFT -> 9.9f
            Side.RIGHT -> 0.1f
            null -> 1f
        },
        animationSpec = tween(durationMillis = 400),
        label = "leftWeight",
    )
    val rightWeight by animateFloatAsState(
        targetValue = when (expanded) {
            Side.LEFT -> 0.1f
            Side.RIGHT -> 9.9f
            null -> 1f
        },
        animationSpec = tween(durationMillis = 400),
        label = "rightWeight",
    )

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            BrowserPane(
                side = Side.LEFT,
                state = left,
                isFocused = focused == Side.LEFT,
                storageGranted = storageGranted,
                breadcrumb = viewModel.breadcrumbOf(Side.LEFT, left.path),
                onPaneTouched = { viewModel.focus(Side.LEFT) },
                onExpandToggle = { viewModel.toggleExpand(Side.LEFT) },
                onNavigate = { viewModel.navigate(Side.LEFT, it) },
                onNavigateUp = { viewModel.navigateUp(Side.LEFT) },
                onBreadcrumb = { viewModel.navigateToBreadcrumb(Side.LEFT, it) },
                onRefresh = { viewModel.refresh(Side.LEFT) },
                onMkdir = { mkdirSide = Side.LEFT },
                modifier = Modifier.weight(leftWeight).fillMaxHeight(),
            )

            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            BrowserPane(
                side = Side.RIGHT,
                state = right,
                isFocused = focused == Side.RIGHT,
                storageGranted = true,
                breadcrumb = viewModel.breadcrumbOf(Side.RIGHT, right.path),
                onPaneTouched = { viewModel.focus(Side.RIGHT) },
                onExpandToggle = { viewModel.toggleExpand(Side.RIGHT) },
                onNavigate = { viewModel.navigate(Side.RIGHT, it) },
                onNavigateUp = { viewModel.navigateUp(Side.RIGHT) },
                onBreadcrumb = { viewModel.navigateToBreadcrumb(Side.RIGHT, it) },
                onRefresh = { viewModel.refresh(Side.RIGHT) },
                onMkdir = { mkdirSide = Side.RIGHT },
                modifier = Modifier.weight(rightWeight).fillMaxHeight(),
            )
        }

        // 展开态:被压一侧的边缘把手,点击恢复双栏
        when (expanded) {
            Side.LEFT -> EdgeHandle(
                alignment = Alignment.CenterEnd,
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                onClick = viewModel::collapse,
            )

            Side.RIGHT -> EdgeHandle(
                alignment = Alignment.CenterStart,
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                onClick = viewModel::collapse,
            )

            null -> Unit
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    mkdirSide?.let { side ->
        MkdirDialog(
            onConfirm = { name ->
                viewModel.mkdir(side, name)
                mkdirSide = null
            },
            onDismiss = { mkdirSide = null },
        )
    }
}

// ---- 单侧浏览器 ----

@Composable
private fun BrowserPane(
    side: Side,
    state: BrowserState,
    isFocused: Boolean,
    storageGranted: Boolean,
    breadcrumb: List<String>,
    onPaneTouched: () -> Unit,
    onExpandToggle: () -> Unit,
    onNavigate: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onBreadcrumb: (Int) -> Unit,
    onRefresh: () -> Unit,
    onMkdir: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier.clickable(interactionSource = null, indication = null, onClick = onPaneTouched),
    ) {
        // 标题行:侧名 + 展开开关 + 刷新
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(side.nameForUi(), style = MaterialTheme.typography.labelLarge, color = accent)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onExpandToggle, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("展开", style = MaterialTheme.typography.labelMedium)
            }
            FilledTonalIconButton(onClick = onRefresh, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(17.dp))
            }
        }

        BreadcrumbRow(breadcrumb, onBreadcrumb)

        // 聚焦指示条
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
        )

        // 内容区
        Box(Modifier.weight(1f)) {
            when {
                !isFocused -> CompactFileList(state, hasParent = breadcrumb.size > 1, onNavigate, onNavigateUp)
                else -> FocusedFileList(state, hasParent = breadcrumb.size > 1, onNavigate, onNavigateUp)
            }

            if (side == Side.LEFT && !storageGranted) {
                StoragePermissionCard(Modifier.align(Alignment.Center))
            }
        }

        // 底部操作条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isFocused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onMkdir) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建文件夹", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${state.entries.size} 项",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

/** 聚焦侧:单列列表(拍板) */
@Composable
private fun FocusedFileList(
    state: BrowserState,
    hasParent: Boolean,
    onNavigate: (String) -> Unit,
    onNavigateUp: () -> Unit,
) {
    PaneContent(state) { items ->
        LazyColumn(Modifier.fillMaxSize()) {
            if (hasParent) {
                item(key = "..") {
                    FileRow(FileItem("..", true, 0, ""), onOpen = onNavigateUp)
                }
            }
            items(items, key = { it.name }) { item ->
                FileRow(item) { onOpen ->
                    if (onOpen.isDir) onNavigate(onOpen.name)
                    // 文件点击:M3 接入多选/上传
                }
            }
        }
    }
}

/** 非聚焦侧:两列紧凑网格(拍板,SplitLanzou 同款) */
@Composable
private fun CompactFileList(
    state: BrowserState,
    hasParent: Boolean,
    onNavigate: (String) -> Unit,
    onNavigateUp: () -> Unit,
) {
    PaneContent(state) { items ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (hasParent) {
                item(key = "..", span = { GridItemSpan(2) }) {
                    FileRow(FileItem("..", true, 0, ""), onOpen = onNavigateUp)
                }
            }
            items(items, key = { it.name }) { item ->
                CompactFileCell(item) {
                    if (item.isDir) onNavigate(item.name)
                }
            }
        }
    }
}

/** 内容区的加载/错误/空态包装 */
@Composable
private fun PaneContent(state: BrowserState, content: @Composable (List<FileItem>) -> Unit) {
    when {
        state.loading && state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        state.error != null -> Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Text(
                state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "空文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> content(state.entries)
    }
}

@Composable
private fun FileRow(item: FileItem, onOpen: (FileItem) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(item) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (item.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.name != "..") {
                Text(
                    "${formatBytes(item.size)} · ${item.modifiedText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CompactFileCell(item: FileItem, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (item.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BreadcrumbRow(segments: List<String>, onNavigate: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(segments) { index, name ->
            if (index > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (index == segments.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                color = if (index == segments.lastIndex) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .clickable { onNavigate(index) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun StoragePermissionCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(modifier = modifier.padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("需要「所有文件访问」权限", style = MaterialTheme.typography.titleSmall)
            Text(
                "浏览手机文件需要此权限；本应用只在你主动选择文件后上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ButtonRow(context)
        }
    }
}

@Composable
private fun ButtonRow(context: android.content.Context) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = {
            val intents = listOf(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
            intents.firstOrNull { runCatching { context.startActivity(it) }.isSuccess }
        }) {
            Text("去授权")
        }
    }
}

@Composable
private fun EdgeHandle(alignment: Alignment, icon: ImageVector, onClick: () -> Unit) {
    val shape = when (alignment) {
        Alignment.CenterEnd -> RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
        else -> RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
    }
    FilledTonalIconButton(
        onClick = onClick,
        shape = shape,
        modifier = Modifier
            .align(alignment)
            .padding(vertical = 32.dp)
            .size(width = 24.dp, height = 64.dp),
    ) {
        Icon(icon, contentDescription = "恢复双栏", modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MkdirDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("文件夹名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun Side.nameForUi(): String = when (this) {
    Side.LEFT -> "本地"
    Side.RIGHT -> "远程"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", mb)
    return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0)
}
