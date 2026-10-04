package com.tomcat927.miscuploader.ui.home

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomcat927.miscuploader.core.OpenListApiException
import com.tomcat927.miscuploader.data.ConnectionManager
import com.tomcat927.miscuploader.data.SettingsRepository
import com.tomcat927.miscuploader.data.UploadMode
import com.tomcat927.miscuploader.data.UploadPlanning
import com.tomcat927.miscuploader.data.UploadRepository
import com.tomcat927.miscuploader.data.UploadTask
import com.tomcat927.miscuploader.ui.viewer.FileKind
import com.tomcat927.miscuploader.ui.viewer.REMOTE_PREVIEW_LIMIT
import com.tomcat927.miscuploader.ui.viewer.ViewerRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 双栏主页面(拍板:左=本地,右=远程;交互借 SplitLanzou——触摸聚焦、展开动画、非聚焦侧两列)。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: ConnectionManager,
    private val uploadRepository: UploadRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val localRoot: String = Environment.getExternalStorageDirectory().absolutePath

    private val _focusedSide = MutableStateFlow(Side.LEFT)
    val focusedSide: StateFlow<Side> = _focusedSide.asStateFlow()

    private val _expandedSide = MutableStateFlow<Side?>(null)
    val expandedSide: StateFlow<Side?> = _expandedSide.asStateFlow()

    private val _left = MutableStateFlow(BrowserState(path = localRoot))
    val left: StateFlow<BrowserState> = _left.asStateFlow()

    private val _right = MutableStateFlow(BrowserState(path = "/"))
    val right: StateFlow<BrowserState> = _right.asStateFlow()

    private val _storageGranted = MutableStateFlow(false)
    val storageGranted: StateFlow<Boolean> = _storageGranted.asStateFlow()

    /** 一次性提示(建目录结果等) */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 多选(拍板 M3:仅本地侧可选;换目录即清空) */
    private val _selectedLeft = MutableStateFlow<Set<String>>(emptySet())
    val selectedLeft: StateFlow<Set<String>> = _selectedLeft.asStateFlow()
    val selectionMode: Boolean
        get() = _selectedLeft.value.isNotEmpty()

    /** 上传模式(拍板 A2:确认框文案与目标规划用) */
    val uploadMode: StateFlow<UploadMode> = settings.uploadModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UploadMode.MANUAL)

    /** 双栏触摸聚焦开关(拍板 2026-10-04:默认开;关闭=两栏恒单列,点击不再切换布局) */
    val touchFocus: StateFlow<Boolean> = settings.touchFocusFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        recheckStoragePermission()
        refresh(Side.LEFT)
        viewModelScope.launch {
            connection.state.collect { state ->
                when (state) {
                    is ConnectionManager.State.Connected -> load(Side.RIGHT, "/", refresh = true)
                    is ConnectionManager.State.Failed ->
                        _right.update { it.copy(loading = false, error = "未连接：${state.message}") }

                    ConnectionManager.State.Connecting,
                    ConnectionManager.State.Idle -> Unit
                }
            }
        }
    }

    // ---- 权限 ----

    /** 有「所有文件访问」权限;R 以下(minSdk 26~28)按传统权限处理,用户设备几乎全部 R+,失败由 listFiles 兜底 */
    fun recheckStoragePermission() {
        _storageGranted.value =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }

    // ---- 双栏交互 ----

    fun focus(side: Side) {
        _focusedSide.value = side
    }

    /** 展开该侧至全屏;再次操作恢复双栏(拍板:400ms 宽度动画) */
    fun toggleExpand(side: Side) {
        _expandedSide.value = if (_expandedSide.value == side) null else side
    }

    fun collapse() {
        _expandedSide.value = null
    }

    // ---- 系统返回键(拍板 2026-10-04:四级返回链,README 使用说明同步) ----

    /**
     * 返回键逐级消费:单侧展开→收起双栏 → 多选→退出多选 → 聚焦侧子目录→返回上级。
     * @return true = 已消费;false = 未消费(两级都在根目录,交由 MainScreen 二次确认退出)
     */
    fun onBackConsumed(): Boolean {
        if (_expandedSide.value != null) {
            collapse()
            return true
        }
        if (selectionMode) {
            clearSelection()
            return true
        }
        val side = _focusedSide.value
        if (breadcrumbOf(side, stateOf(side).path).size > 1) {
            navigateUp(side)
            return true
        }
        return false
    }

    // ---- 多选与上传(M3) ----

    /** 长按进入多选并选中该项 */
    fun onItemLongPress(item: FileItem) {
        _selectedLeft.update { it + item.name }
    }

    /** 多选态下单击切换选中 */
    fun toggleSelect(item: FileItem) {
        _selectedLeft.update { set ->
            if (item.name in set) set - item.name else set + item.name
        }
    }

    fun clearSelection() {
        _selectedLeft.value = emptySet()
    }

    /** 上传已选项(拍板 A2:手动模式 → 远程当前目录;自动模式 → 按各文件 mtime 归 auto/yyyy/MM) */
    fun uploadSelected() {
        val selected = _selectedLeft.value
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val mode = settings.loadUploadModeOnce()
            val manualTarget = _right.value.path
            val currentLocal = _left.value.path
            val tasks = mutableListOf<UploadTask>()
            selected.forEach { name ->
                val f = File(joinPath(currentLocal, name))
                when {
                    f.isFile -> tasks += taskFor(f, name, mode, manualTarget)
                    f.isDirectory -> f.walkTopDown()
                        .filter { it.isFile }
                        .forEach { child ->
                            tasks += taskFor(child, child.path.removePrefix(currentLocal).trimStart('/'), mode, manualTarget)
                        }
                }
            }
            if (tasks.isEmpty()) {
                events.emit("没有可上传的文件")
                return@launch
            }
            uploadRepository.enqueue(tasks)
            _selectedLeft.value = emptySet()
            events.emit("已加入队列：${tasks.size} 个文件")
        }
    }

    private fun taskFor(file: File, rel: String, mode: UploadMode, manualTarget: String): UploadTask =
        UploadTask(
            file = file,
            remoteDir = if (mode == UploadMode.AUTO_DATE) {
                UploadPlanning.autoDirFor(file.lastModified())
            } else {
                manualTarget
            },
            rel = rel,
        )

    // ---- 文件查看(拍板 2026-10-04:图片/文本内置,其余委托系统;先于 A1) ----

    private val _viewerRequest = MutableStateFlow<ViewerRequest?>(null)
    val viewerRequest: StateFlow<ViewerRequest?> = _viewerRequest.asStateFlow()

    /** 文件单击(非多选态):按 FileKind 路由到查看器;远程文本先 fs/get 预检大小,>50MB 免下载直接拒 */
    fun openFile(side: Side, item: FileItem) {
        if (item.isDir) return
        val kind = FileKind.of(item.name)
        if (side == Side.RIGHT && kind == FileKind.TEXT) {
            viewModelScope.launch {
                try {
                    val client = connection.clientOrNull()
                        ?: throw IllegalStateException("未连接——请到「设置」连接服务器")
                    val remote = UploadPlanning.joinRemotePath(stateOf(side).path, item.name)
                    if (client.fileInfo(remote).size > REMOTE_PREVIEW_LIMIT) {
                        events.emit("「${item.name}」超过 50MB，不进文本查看器")
                    } else {
                        openViewer(side, item, kind)
                    }
                } catch (e: Exception) {
                    events.emit(e.message ?: "打开失败")
                }
            }
        } else {
            openViewer(side, item, kind)
        }
    }

    private fun openViewer(side: Side, item: FileItem, kind: FileKind) {
        val state = stateOf(side)
        val items = if (kind == FileKind.IMAGE) {
            state.entries.filter { !it.isDir && FileKind.of(it.name) == FileKind.IMAGE }
        } else {
            listOf(item)
        }
        _viewerRequest.value = ViewerRequest(
            isLocal = side == Side.LEFT,
            kind = kind,
            basePath = state.path,
            items = items,
            index = items.indexOfFirst { it.name == item.name }.coerceAtLeast(0),
        )
    }

    fun closeViewer() {
        _viewerRequest.value = null
    }

    /** 查看器闭包:本地文件(查看器全屏打开期间两栏不可操作,basePath 稳定) */
    fun localFileOf(request: ViewerRequest, item: FileItem): File = File(request.basePath, item.name)

    /** 查看器闭包:远程图片直连 raw_url(Coil 加载,不落盘) */
    suspend fun rawUrlOf(request: ViewerRequest, item: FileItem): String {
        val client = connection.clientOrNull()
            ?: throw IllegalStateException("未连接——请到「设置」连接服务器")
        return client.fileInfo(UploadPlanning.joinRemotePath(request.basePath, item.name)).rawUrl
    }

    /** 查看器闭包:远程文本/其他类型先下载到 cache/remote_preview(经 raw_url 跟随重定向) */
    suspend fun downloadPreview(
        request: ViewerRequest,
        item: FileItem,
        onProgress: (Float) -> Unit,
    ): File {
        val client = connection.clientOrNull()
            ?: throw IllegalStateException("未连接——请到「设置」连接服务器")
        val remote = UploadPlanning.joinRemotePath(request.basePath, item.name)
        val cacheDir = File(context.cacheDir, "remote_preview").apply { mkdirs() }
        val target = File(cacheDir, "${Integer.toHexString(remote.hashCode())}_${item.name}")
        client.downloadTo(remote, target) { sent, total ->
            onProgress(if (total > 0) sent.toFloat() / total else 0f)
        }
        return target
    }

    // ---- 导航 ----

    fun navigate(side: Side, dirName: String) {
        val current = stateOf(side).path
        val newPath = when (side) {
            Side.LEFT -> joinPath(current, dirName)
            Side.RIGHT -> if (current == "/") "/$dirName" else "$current/$dirName"
        }
        load(side, newPath)
    }

    fun navigateUp(side: Side) {
        val breadcrumb = breadcrumbOf(side, stateOf(side).path)
        if (breadcrumb.size <= 1) return
        navigateToBreadcrumb(side, breadcrumb.size - 2)
    }

    fun navigateToBreadcrumb(side: Side, depth: Int) {
        val segments = breadcrumbOf(side, stateOf(side).path)
        if (depth < 0 || depth >= segments.size) return
        val newPath = when (side) {
            Side.LEFT ->
                if (depth == 0) localRoot else "$localRoot/" + segments.drop(1).take(depth).joinToString("/")

            Side.RIGHT ->
                if (depth == 0) "/" else "/" + segments.drop(1).take(depth).joinToString("/")
        }
        load(side, newPath)
    }

    fun refresh(side: Side) = load(side, stateOf(side).path, refresh = true)

    /** 面包屑段:本地 [存储, DCIM, …];远程 [/, docs, …] */
    fun breadcrumbOf(side: Side, path: String): List<String> = when (side) {
        Side.LEFT -> {
            val rel = path.removePrefix(localRoot)
            listOf("存储") + rel.split('/').filter { it.isNotEmpty() }
        }

        Side.RIGHT -> listOf("/") + path.trim('/').split('/').filter { it.isNotEmpty() }
    }

    // ---- 新建文件夹 ----

    fun mkdir(side: Side, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val currentPath = stateOf(side).path
            try {
                when (side) {
                    Side.LEFT -> {
                        val dir = File(joinPath(currentPath, trimmed))
                        when {
                            dir.exists() -> events.emit("「$trimmed」已存在")
                            dir.mkdir() -> events.emit("已创建 $trimmed")
                            else -> events.emit("创建失败（权限不足或目录无效）")
                        }
                    }

                    Side.RIGHT -> {
                        val client = connection.clientOrNull()
                            ?: throw IllegalStateException("未连接——请到「设置」连接服务器")
                        val remote = if (currentPath == "/") "/$trimmed" else "$currentPath/$trimmed"
                        try {
                            client.mkdir(remote)
                            events.emit("已创建 $trimmed")
                        } catch (e: OpenListApiException) {
                            val exists = e.code == 403 || e.message?.contains("exist", ignoreCase = true) == true
                            events.emit(if (exists) "「$trimmed」已存在" else (e.message ?: "创建失败"))
                            return@launch
                        }
                    }
                }
                load(side, currentPath, refresh = true)
            } catch (e: Exception) {
                events.emit(e.message ?: "创建失败")
            }
        }
    }

    // ---- 内部 ----

    private fun load(side: Side, path: String, refresh: Boolean = false) {
        // 换目录即清空多选(拍板:多选不跨目录保留)
        if (side == Side.LEFT && stateFlowOf(side).value.path != path) _selectedLeft.value = emptySet()
        stateFlowOf(side).update { it.copy(path = path, loading = true, error = null) }
        viewModelScope.launch {
            try {
                val entries = when (side) {
                    Side.LEFT -> listLocal(path)
                    Side.RIGHT -> listRemote(path, refresh)
                }
                stateFlowOf(side).update { it.copy(entries = entries, loading = false) }
            } catch (e: Exception) {
                stateFlowOf(side).update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    private suspend fun listLocal(path: String): List<FileItem> = withContext(Dispatchers.IO) {
        val files = File(path).listFiles()
            ?: throw IOException("无法读取该目录（系统目录或权限不足）")
        files.map { f ->
            FileItem(
                name = f.name,
                isDir = f.isDirectory,
                size = f.length(),
                modifiedText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(f.lastModified())),
            )
        }.sortedWith(ITEM_ORDER)
    }

    private suspend fun listRemote(path: String, refresh: Boolean): List<FileItem> {
        val client = connection.clientOrNull()
            ?: throw IllegalStateException("未连接——请到「设置」连接服务器")
        // 拍板(M2):浏览一律 refresh=false(避免频繁刷 OpenList 目录缓存);上传成功后的定向刷新在 M3
        return client.list(path, refresh = refresh)
            .map { FileItem(it.name, it.isDir, it.size, it.modified.orEmpty().replace('T', ' ').take(16)) }
            .sortedWith(ITEM_ORDER)
    }

    private fun stateFlowOf(side: Side) = if (side == Side.LEFT) _left else _right

    private fun stateOf(side: Side): BrowserState = stateFlowOf(side).value

    private fun joinPath(path: String, name: String): String = "$path/$name"

    companion object {
        private val ITEM_ORDER =
            compareByDescending<FileItem> { it.isDir }.thenBy { it.name.lowercase() }
    }
}
