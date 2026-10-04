package com.tomcat927.miscuploader.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
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
import com.tomcat927.miscuploader.core.GalleryLink
import com.tomcat927.miscuploader.data.UploadMode
import com.tomcat927.miscuploader.ui.viewer.FileViewerDialog

/**
 * 双栏主界面(拍板借 SplitLanzou:触摸聚焦、聚焦侧单列/非聚焦侧两列、400ms 展开动画 + 边缘把手)。
 * M3:本地侧多选 → 上传到远程当前目录(确认框 + Room 队列 + 前台服务)。
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val left by viewModel.left.collectAsState()
    val right by viewModel.right.collectAsState()
    val focused by viewModel.focusedSide.collectAsState()
    val expanded by viewModel.expandedSide.collectAsState()
    val storageGranted by viewModel.storageGranted.collectAsState()
    val selectedLeft by viewModel.selectedLeft.collectAsState()
    val selectedRight by viewModel.selectedRight.collectAsState()
    val uploadMode by viewModel.uploadMode.collectAsState()
    val touchFocus by viewModel.touchFocus.collectAsState()
    val pigalleryBase by viewModel.pigalleryBase.collectAsState()
    val leftCategory by viewModel.leftCategory.collectAsState()
    val rightCategory by viewModel.rightCategory.collectAsState()
    val leftSort by viewModel.leftSort.collectAsState()
    val rightSort by viewModel.rightSort.collectAsState()
    val viewerRequest by viewModel.viewerRequest.collectAsState()
    val context = LocalContext.current
    val galleryUrl = GalleryLink.forDir(pigalleryBase, right.path)
    val snackbarHostState = remember { SnackbarHostState() }
    var mkdirSide by remember { mutableStateOf<Side?>(null) }
    var uploadConfirm by remember { mutableStateOf(false) }
    var remoteMovePicker by remember { mutableStateOf(false) }
    var remoteDeleteConfirm by remember { mutableStateOf(false) }

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
                focusEnabled = touchFocus,
                storageGranted = storageGranted,
                breadcrumb = viewModel.breadcrumbOf(Side.LEFT, left.path),
                selectionMode = selectedLeft.isNotEmpty(),
                selected = selectedLeft,
                category = leftCategory,
                onCategory = { viewModel.setCategory(Side.LEFT, it) },
                sort = leftSort,
                onSort = { viewModel.setSort(Side.LEFT, it) },
                onSelectAll = { viewModel.selectAll(Side.LEFT) },
                onPaneTouched = { viewModel.focus(Side.LEFT) },
                onExpandToggle = { viewModel.toggleExpand(Side.LEFT) },
                onOpenGallery = null,
                onNavigate = { viewModel.navigate(Side.LEFT, it) },
                onNavigateUp = { viewModel.navigateUp(Side.LEFT) },
                onBreadcrumb = { viewModel.navigateToBreadcrumb(Side.LEFT, it) },
                onRefresh = { viewModel.refresh(Side.LEFT) },
                onMkdir = { mkdirSide = Side.LEFT },
                onOpenFile = { viewModel.openFile(Side.LEFT, it) },
                onItemLongPress = viewModel::onItemLongPress,
                onItemToggleSelect = viewModel::toggleSelect,
                onUploadSelection = { uploadConfirm = true },
                onMoveSelection = {},
                onDeleteSelection = {},
                onCancelSelection = viewModel::clearSelection,
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
                focusEnabled = touchFocus,
                storageGranted = true,
                breadcrumb = viewModel.breadcrumbOf(Side.RIGHT, right.path),
                selectionMode = selectedRight.isNotEmpty(),
                selected = selectedRight,
                category = rightCategory,
                onCategory = { viewModel.setCategory(Side.RIGHT, it) },
                sort = rightSort,
                onSort = { viewModel.setSort(Side.RIGHT, it) },
                onSelectAll = { viewModel.selectAll(Side.RIGHT) },
                onPaneTouched = { viewModel.focus(Side.RIGHT) },
                onExpandToggle = { viewModel.toggleExpand(Side.RIGHT) },
                onOpenGallery = galleryUrl?.let { url ->
                    {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                },
                onNavigate = { viewModel.navigate(Side.RIGHT, it) },
                onNavigateUp = { viewModel.navigateUp(Side.RIGHT) },
                onBreadcrumb = { viewModel.navigateToBreadcrumb(Side.RIGHT, it) },
                onRefresh = { viewModel.refresh(Side.RIGHT) },
                onMkdir = { mkdirSide = Side.RIGHT },
                onOpenFile = { viewModel.openFile(Side.RIGHT, it) },
                onItemLongPress = viewModel::onItemLongPressRight,
                onItemToggleSelect = viewModel::toggleSelectRight,
                onUploadSelection = {},
                onMoveSelection = { remoteMovePicker = true },
                onDeleteSelection = { remoteDeleteConfirm = true },
                onCancelSelection = viewModel::clearSelectionRight,
                modifier = Modifier.weight(rightWeight).fillMaxHeight(),
            )
        }

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

    // 文件查看(拍板 2026-10-04):全屏 Dialog 独立窗口,盖住底部导航,返回键关闭
    viewerRequest?.let { request ->
        FileViewerDialog(
            request = request,
            localFile = { item -> viewModel.localFileOf(request, item) },
            remoteRawUrl = { item -> viewModel.rawUrlOf(request, item) },
            remoteDownload = { item, onProgress -> viewModel.downloadPreview(request, item, onProgress) },
            onClose = viewModel::closeViewer,
        )
    }

    // 远程整理(拍板 2026-10-04):移动目标选择器 / 删除硬确认
    if (remoteMovePicker && selectedRight.isNotEmpty()) {
        RemoteDirPickerDialog(
            srcDir = right.path,
            listDirs = viewModel::listRemoteDirs,
            onConfirm = { dst ->
                viewModel.moveSelectedRightTo(dst)
                remoteMovePicker = false
            },
            onDismiss = { remoteMovePicker = false },
        )
    }

    if (remoteDeleteConfirm && selectedRight.isNotEmpty()) {
        DeleteConfirmDialog(
            paths = selectedRight.map { if (right.path == "/") "/$it" else "${right.path}/$it" },
            onConfirm = {
                viewModel.deleteSelectedRight()
                remoteDeleteConfirm = false
            },
            onDismiss = { remoteDeleteConfirm = false },
        )
    }

    if (uploadConfirm && selectedLeft.isNotEmpty()) {
        val autoMode = uploadMode == UploadMode.AUTO_DATE
        UploadConfirmDialog(
            count = selectedLeft.size,
            targetDesc = if (autoMode) {
                "auto/年/月（按各文件修改时间自动归类）"
            } else {
                viewModel.breadcrumbOf(Side.RIGHT, right.path).joinToString("/")
            },
            onConfirm = {
                viewModel.uploadSelected()
                uploadConfirm = false
            },
            onDismiss = { uploadConfirm = false },
        )
    }
}

// ---- 单侧浏览器 ----

@Composable
private fun BrowserPane(
    side: Side,
    state: BrowserState,
    isFocused: Boolean,
    focusEnabled: Boolean,
    storageGranted: Boolean,
    breadcrumb: List<String>,
    selectionMode: Boolean,
    selected: Set<String>,
    category: FileCategory,
    onCategory: (FileCategory) -> Unit,
    sort: SortSpec,
    onSort: (SortField) -> Unit,
    onSelectAll: () -> Unit,
    onPaneTouched: () -> Unit,
    onExpandToggle: () -> Unit,
    /** PiGallery2 深链(D1):null = 未配置不显示;仅远程栏传入 */
    onOpenGallery: (() -> Unit)?,
    onNavigate: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onBreadcrumb: (Int) -> Unit,
    onRefresh: () -> Unit,
    onMkdir: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onItemLongPress: (FileItem) -> Unit,
    onItemToggleSelect: (FileItem) -> Unit,
    onUploadSelection: () -> Unit,
    onMoveSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onCancelSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier.clickable(interactionSource = null, indication = null, onClick = onPaneTouched),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(side.nameForUi(), style = MaterialTheme.typography.labelLarge, color = accent)
            Spacer(Modifier.weight(1f))
            onOpenGallery?.let { open ->
                FilledTonalIconButton(onClick = open, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.PhotoLibrary,
                        contentDescription = "在 PiGallery2 打开当前目录",
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            TextButton(onClick = onExpandToggle, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("展开", style = MaterialTheme.typography.labelMedium)
            }
            FilledTonalIconButton(onClick = onRefresh, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(17.dp))
            }
        }

        if (selectionMode) {
            // 多选态顶栏:取消/全选/已选(替换面包屑,半栏宽度取舍——反选未做)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancelSelection, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("取消")
                }
                TextButton(onClick = onSelectAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("全选")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "已选 ${selected.size}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        } else {
            BreadcrumbRow(breadcrumb, onBreadcrumb)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
        )

        // 工具条:类型过滤 chips + 排序(拍板 2026-10-05,MT 同款能力;目录恒显示不受过滤)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FileCategory.entries.forEach { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { onCategory(c) },
                        label = { Text(c.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            SortMenuButton(sort = sort, onSort = onSort)
        }

        Box(Modifier.weight(1f)) {
            when {
                // 聚焦机制关闭(拍板 2026-10-04)或本栏聚焦 → 单列;仅开启且非聚焦 → 两列总览
                !focusEnabled || isFocused -> FocusedFileList(
                    state, breadcrumb.size > 1, onNavigate, onNavigateUp,
                    onItemLongPress = onItemLongPress, selectionMode = selectionMode, selected = selected,
                    onItemToggleSelect = onItemToggleSelect, onOpenFile = onOpenFile,
                )

                else -> CompactFileList(
                    state, breadcrumb.size > 1, onNavigate, onNavigateUp,
                    onItemLongPress = onItemLongPress, selectionMode = selectionMode, selected = selected,
                    onItemToggleSelect = onItemToggleSelect, selectionEnabled = true,
                    onOpenFile = onOpenFile,
                )
            }

            if (side == Side.LEFT && !storageGranted) {
                StoragePermissionCard(Modifier.align(Alignment.Center))
            }
        }

        // 底部操作条:多选态只留主操作(取消/全选在顶部栏);普通态=新建文件夹+目录统计
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isFocused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Spacer(Modifier.weight(1f))
                when (side) {
                    Side.LEFT -> Button(
                        onClick = onUploadSelection,
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.padding(end = 10.dp),
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("上传")
                    }

                    Side.RIGHT -> {
                        Button(onClick = onMoveSelection, enabled = selected.isNotEmpty()) {
                            Text("移动到…")
                        }
                        TextButton(
                            onClick = onDeleteSelection,
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.padding(end = 10.dp),
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else {
                TextButton(onClick = onMkdir) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建文件夹", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                val dirs = state.entries.count { it.isDir }
                Text(
                    "文件夹 $dirs 文件 ${state.entries.size - dirs}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
    }
}

/** 排序菜单(拍板 2026-10-05):同字段再点翻转方向;时间默认新在前 */
@Composable
private fun SortMenuButton(sort: SortSpec, onSort: (SortField) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .padding(end = 6.dp)
                .size(30.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = "排序方式",
                modifier = Modifier.size(17.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortField.entries.forEach { field ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (field) {
                                SortField.NAME -> "按名称"
                                SortField.SIZE -> "按大小"
                                SortField.TIME -> "按时间"
                            } + if (sort.field == field) (if (sort.asc) " ↑" else " ↓") else "",
                        )
                    },
                    onClick = {
                        onSort(field)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 聚焦侧:单列列表(拍板);selectionMode 时勾选切换 */
@Composable
private fun FocusedFileList(
    state: BrowserState,
    hasParent: Boolean,
    onNavigate: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onItemLongPress: (FileItem) -> Unit,
    selectionMode: Boolean,
    selected: Set<String>,
    onItemToggleSelect: (FileItem) -> Unit,
    onOpenFile: (FileItem) -> Unit,
) {
    PaneContent(state) { entries ->
        LazyColumn(Modifier.fillMaxSize()) {
            if (hasParent && !selectionMode) {
                item(key = "..") {
                    FileRow(FileItem("..", true, 0, ""), selected = false, selectionMode = false, onOpen = { onNavigateUp() })
                }
            }
            items(entries, key = { it.name }) { item ->
                FileRow(
                    item = item,
                    selected = item.name in selected,
                    selectionMode = selectionMode,
                    onOpen = { opened ->
                        if (selectionMode) onItemToggleSelect(opened)
                        else if (opened.isDir) onNavigate(opened.name)
                        else onOpenFile(opened)
                    },
                    onLongPress = { onItemLongPress(it) },
                )
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
    onItemLongPress: (FileItem) -> Unit,
    selectionMode: Boolean,
    selected: Set<String>,
    onItemToggleSelect: (FileItem) -> Unit,
    selectionEnabled: Boolean,
    onOpenFile: (FileItem) -> Unit,
) {
    PaneContent(state) { entries ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (hasParent && !selectionMode) {
                item(key = "..", span = { GridItemSpan(2) }) {
                    FileRow(FileItem("..", true, 0, ""), selected = false, selectionMode = false, onOpen = { onNavigateUp() })
                }
            }
            items(entries, key = { it.name }) { item ->
                CompactFileCell(
                    item = item,
                    selected = item.name in selected,
                    selectionMode = selectionMode && selectionEnabled,
                    onOpen = {
                        if (selectionMode && selectionEnabled) onItemToggleSelect(item)
                        else if (item.isDir) onNavigate(item.name)
                        else onOpenFile(item)
                    },
                    onLongPress = if (selectionEnabled) {
                        { onItemLongPress(item) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: (FileItem) -> Unit,
    onLongPress: ((FileItem) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpen(item) },
                onLongClick = onLongPress?.let { handler -> { handler(item) } },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onOpen(item) })
        } else {
            Icon(
                if (item.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (item.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactFileCell(
    item: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onOpen() })
        } else {
            Icon(
                if (item.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (item.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

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
}

@Composable
private fun BoxScope.EdgeHandle(alignment: Alignment, icon: ImageVector, onClick: () -> Unit) {
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

@Composable
private fun UploadConfirmDialog(count: Int, targetDesc: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上传") },
        text = {
            Text("已选择 $count 个文件（文件夹按内部结构展开），将上传到：\n$targetDesc")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("执行上传") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 远程移动目标选择器(拍板 2026-10-04):从仓库根起导航,仅列目录;目标不能是来源目录 */
@Composable
private fun RemoteDirPickerDialog(
    srcDir: String,
    listDirs: suspend (String) -> List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf("/") }
    var dirs by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        error = null
        try {
            dirs = listDirs(path)
        } catch (e: Exception) {
            dirs = emptyList()
            error = e.message ?: "加载失败"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到") },
        text = {
            Column {
                Text(
                    "目标目录：$path",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.height(280.dp)) {
                    val loaded = dirs
                    when {
                        loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }

                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            if (path != "/") {
                                item(key = "..") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                path = path.substringBeforeLast('/').ifEmpty { "/" }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("..", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            items(loaded, key = { it }) { name ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            path = if (path == "/") "/$name" else "$path/$name"
                                        }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(name, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(path) }, enabled = path != srcDir) {
                Text("移动到这里")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 远程删除硬确认(拍板:明示完整路径;文件夹递归删;误删由云盘网页回收站兜底) */
@Composable
private fun DeleteConfirmDialog(paths: List<String>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除", color = MaterialTheme.colorScheme.error) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("将永久删除 ${paths.size} 项（文件夹连同内容一起删除）：")
                Text(
                    paths.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "误删可在存储云盘的网页端回收站尝试恢复（保留期由云盘决定）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
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
