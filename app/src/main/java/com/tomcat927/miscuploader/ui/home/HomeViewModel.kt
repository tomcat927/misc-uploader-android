package com.tomcat927.miscuploader.ui.home

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomcat927.miscuploader.core.OpenListApiException
import com.tomcat927.miscuploader.data.ConnectionManager
import com.tomcat927.miscuploader.data.RootEntry
import com.tomcat927.miscuploader.data.RootShell
import com.tomcat927.miscuploader.data.SettingsRepository
import com.tomcat927.miscuploader.data.UploadMode
import com.tomcat927.miscuploader.data.UploadPlanning
import com.tomcat927.miscuploader.data.UploadRepository
import com.tomcat927.miscuploader.data.UploadTask
import com.tomcat927.miscuploader.ui.viewer.FileKind
import com.tomcat927.miscuploader.ui.viewer.REMOTE_PREVIEW_LIMIT
import com.tomcat927.miscuploader.ui.viewer.ViewerRequest
import com.tomcat927.miscuploader.ui.viewer.openWithSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val rootShell: RootShell,
    private val uploadRepository: UploadRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val localRoot: String = Environment.getExternalStorageDirectory().absolutePath

    private val _focusedSide = MutableStateFlow(Side.LEFT)
    val focusedSide: StateFlow<Side> = _focusedSide.asStateFlow()

    private val _expandedSide = MutableStateFlow<Side?>(null)
    val expandedSide: StateFlow<Side?> = _expandedSide.asStateFlow()

    private val _left = MutableStateFlow(BrowserState(path = localRoot))
    private val _right = MutableStateFlow(BrowserState(path = "/"))

    /** 显示隐藏文件(拍板 2026-10-04:默认关;"." 前缀=隐藏,本地/远程同规则) */
    private val showHidden: StateFlow<Boolean> = settings.showHiddenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ---- 浏览过滤与排序(拍板 2026-10-05,MT 同款能力;纯展示层) ----

    private val _leftCategory = MutableStateFlow(FileCategory.ALL)
    private val _rightCategory = MutableStateFlow(FileCategory.ALL)
    private val _leftSort = MutableStateFlow(SortSpec(SortField.NAME))
    private val _rightSort = MutableStateFlow(SortSpec(SortField.NAME))

    val leftCategory: StateFlow<FileCategory> = _leftCategory.asStateFlow()
    val rightCategory: StateFlow<FileCategory> = _rightCategory.asStateFlow()
    val leftSort: StateFlow<SortSpec> = _leftSort.asStateFlow()
    val rightSort: StateFlow<SortSpec> = _rightSort.asStateFlow()

    // ---- 目录内实时过滤(拍板 2026-10-05,MT 同款;与隐藏/类型/排序叠加,换目录即清空) ----

    private val _leftQuery = MutableStateFlow("")
    private val _rightQuery = MutableStateFlow("")
    val leftQuery: StateFlow<String> = _leftQuery.asStateFlow()
    val rightQuery: StateFlow<String> = _rightQuery.asStateFlow()

    fun setQuery(side: Side, query: String) {
        queryOf(side).value = query
    }

    private fun queryOf(side: Side) = if (side == Side.LEFT) _leftQuery else _rightQuery

    fun setCategory(side: Side, category: FileCategory) {
        categoryOf(side).value = category
    }

    /** 同字段再点 = 翻转方向;切新字段时时间默认新在前 */
    fun setSort(side: Side, field: SortField) {
        val cur = sortOf(side).value
        sortOf(side).value = if (cur.field == field) {
            cur.copy(asc = !cur.asc)
        } else {
            SortSpec(field, asc = field != SortField.TIME)
        }
    }

    private fun categoryOf(side: Side) = if (side == Side.LEFT) _leftCategory else _rightCategory

    private fun sortOf(side: Side) = if (side == Side.LEFT) _leftSort else _rightSort

    /**
     * 展示侧统一加工(拍板 2026-10-05):隐藏过滤 → 目录内关键词过滤 → 类型过滤 → 排序。
     * 搜索词非空时目录也参与名字匹配(不再"目录恒显示"),否则结果被目录盖住;空词 = 行为不变。
     */
    private fun processEntries(
        entries: List<FileItem>,
        show: Boolean,
        query: String,
        category: FileCategory,
        sort: SortSpec,
    ): List<FileItem> = entries
        .filter { show || !it.name.startsWith(".") }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        // 类型过滤:目录恒过本关(关键词关已先行过滤不匹配的目录),文件按分类
        .filter { it.isDir || category == FileCategory.ALL || FileCategory.of(it.name) == category }
        .let { list ->
            val cmp = when (sort.field) {
                SortField.NAME -> compareBy<FileItem> { it.name.lowercase() }
                SortField.SIZE -> compareBy<FileItem> { it.size }
                SortField.TIME -> compareBy<FileItem> { it.modifiedText }
            }
            list.sortedWith(compareByDescending<FileItem> { it.isDir }.then(if (sort.asc) cmp else cmp.reversed()))
        }

    /** 浏览列表 = 完整条目按「显示隐藏 + 关键词 + 类型过滤 + 排序」加工(切换即生效,无需刷新) */
    val left: StateFlow<BrowserState> = combine(
        _left, settings.showHiddenFlow, _leftQuery, _leftCategory, _leftSort,
    ) { state, show, query, cat, sort ->
        state.copy(
            entries = processEntries(state.entries, show, query, cat, sort),
            totalCount = state.entries.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _left.value)

    val right: StateFlow<BrowserState> = combine(
        _right, settings.showHiddenFlow, _rightQuery, _rightCategory, _rightSort,
    ) { state, show, query, cat, sort ->
        state.copy(
            entries = processEntries(state.entries, show, query, cat, sort),
            totalCount = state.entries.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _right.value)

    private val _storageGranted = MutableStateFlow(false)
    val storageGranted: StateFlow<Boolean> = _storageGranted.asStateFlow()

    /** 一次性提示(建目录结果等) */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 多选(拍板 M3:本地侧可选,2026-10-04 起远程侧同样可选用于整理;换目录即清空) */
    private val _selectedLeft = MutableStateFlow<Set<String>>(emptySet())
    val selectedLeft: StateFlow<Set<String>> = _selectedLeft.asStateFlow()
    val selectionMode: Boolean
        get() = _selectedLeft.value.isNotEmpty()

    // ---- 远程多选与整理(拍板 2026-10-04:移动+删除;根目录 auto/ 本体受保护) ----

    /** 受保护条目:仓库根的 auto/ 本体(其下文件/子目录可自由整理) */
    private fun isProtectedRemoteEntry(path: String, name: String): Boolean =
        path == "/" && name == UploadPlanning.AUTO_ROOT

    private val _selectedRight = MutableStateFlow<Set<String>>(emptySet())
    val selectedRight: StateFlow<Set<String>> = _selectedRight.asStateFlow()

    fun onItemLongPressRight(item: FileItem) {
        if (item.isDir && isProtectedRemoteEntry(_right.value.path, item.name)) {
            viewModelScope.launch { events.emit("auto/ 目录受保护，不能移动或删除") }
            return
        }
        _selectedRight.update { it + item.name }
    }

    fun toggleSelectRight(item: FileItem) {
        if (item.isDir && isProtectedRemoteEntry(_right.value.path, item.name)) {
            viewModelScope.launch { events.emit("auto/ 目录受保护，不能移动或删除") }
            return
        }
        _selectedRight.update { set -> if (item.name in set) set - item.name else set + item.name }
    }

    fun clearSelectionRight() {
        _selectedRight.value = emptySet()
    }

    /** 远程多选移动(auto/ 根不可作为目标,防散件进归档根;成功后源刷新+目标预热索引) */
    fun moveSelectedRightTo(dstDir: String) {
        val selected = _selectedRight.value
        if (selected.isEmpty()) return
        val srcDir = _right.value.path
        viewModelScope.launch {
            when {
                dstDir == srcDir -> events.emit("目标目录与当前目录相同")
                dstDir == "/${UploadPlanning.AUTO_ROOT}" ->
                    events.emit("auto/ 根目录不允许作为目标，请选其子目录")

                else -> try {
                    val client = connection.clientOrNull()
                        ?: throw IllegalStateException("未连接——请到「设置」连接服务器")
                    client.move(srcDir, selected.toList(), dstDir)
                    _selectedRight.value = emptySet()
                    events.emit("已移动 ${selected.size} 项到 $dstDir")
                    runCatching { client.list(dstDir, refresh = true) } // 预热 OpenList 缓存(对齐上传语义)
                    load(Side.RIGHT, srcDir, refresh = true)
                } catch (e: Exception) {
                    events.emit(e.message ?: "移动失败")
                }
            }
        }
    }

    /** 远程多选删除(硬确认框在 UI 层;文件夹递归删;误删由云盘网页回收站兜底) */
    fun deleteSelectedRight() {
        val selected = _selectedRight.value
        if (selected.isEmpty()) return
        val dir = _right.value.path
        viewModelScope.launch {
            try {
                val client = connection.clientOrNull()
                    ?: throw IllegalStateException("未连接——请到「设置」连接服务器")
                client.remove(dir, selected.toList())
                _selectedRight.value = emptySet()
                events.emit("已删除 ${selected.size} 项")
                load(Side.RIGHT, dir, refresh = true)
            } catch (e: Exception) {
                events.emit(e.message ?: "删除失败")
            }
        }
    }

    /** 「移动到…」目录选择器数据源:某远程目录下的子目录(仅目录,名称升序) */
    suspend fun listRemoteDirs(path: String): List<String> {
        val client = connection.clientOrNull()
            ?: throw IllegalStateException("未连接——请到「设置」连接服务器")
        return client.list(path)
            .filter { it.isDir }
            .map { it.name }
            .sortedWith(compareBy { it.lowercase() })
    }

    /** 上传模式(拍板 A2:确认框文案与目标规划用) */
    val uploadMode: StateFlow<UploadMode> = settings.uploadModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UploadMode.MANUAL)

    /** 双栏触摸聚焦开关(拍板 2026-10-04:默认开;关闭=两栏恒单列,点击不再切换布局) */
    val touchFocus: StateFlow<Boolean> = settings.touchFocusFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** PiGallery2 地址(D1:可选,空 = 远程栏不显示相册按钮) */
    val pigalleryBase: StateFlow<String> = settings.pigalleryBaseFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

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

    /** 全选 = 当前可见条目(含目录;右栏剔除受保护 auto);与隐藏/搜索/类型/排序联动 */
    fun selectAll(side: Side) {
        val state = stateOf(side)
        val names = processEntries(
            state.entries, showHidden.value, queryOf(side).value, categoryOf(side).value, sortOf(side).value,
        )
            .filterNot { side == Side.RIGHT && it.isDir && isProtectedRemoteEntry(state.path, it.name) }
            .map { it.name }
            .toSet()
        if (side == Side.LEFT) _selectedLeft.value = names else _selectedRight.value = names
    }

    /** 上传已选项(拍板 A2:手动模式 → 远程当前目录;自动模式 → 按各文件 mtime 归 auto/yyyy/MM) */
    fun uploadSelected() {
        val selected = _selectedLeft.value
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val mode = settings.loadUploadModeOnce()
            val manualTarget = _right.value.path
            val currentLocal = _left.value.path
            if (_left.value.viaRoot) {
                // root 桥入队(拍板 2026-10-05):su 拷缓存后走统一入队,归类语义与普通路径一致
                events.emit("正在通过 root 读取所选内容…")
                try {
                    val entriesByName = _left.value.entries.associateBy { it.name }
                    val rootEntries = selected.mapNotNull(entriesByName::get).flatMap { item ->
                        if (item.isDir) {
                            rootShell.listFilesRecursive(joinPath(currentLocal, item.name))
                        } else {
                            listOf(
                                RootEntry(
                                    path = joinPath(currentLocal, item.name),
                                    name = item.name,
                                    isDir = false,
                                    size = item.size,
                                    mtimeMs = item.mtimeMs ?: System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                    val count = uploadRepository.enqueueFromRoot(currentLocal, rootEntries, mode, manualTarget)
                    if (count > 0) {
                        _selectedLeft.value = emptySet()
                        events.emit("已加入队列：$count 个文件")
                    } else {
                        events.emit("没有可上传的文件")
                    }
                } catch (e: Exception) {
                    events.emit(e.message ?: "root 读取失败")
                }
                return@launch
            }
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
        if (kind == FileKind.OTHER && side == Side.LEFT) {
            // 本地其他类型:不进查看器,直接弹系统「打开方式」(MT 管理器同款,拍板 2026-10-05)
            openLocalWithSystem(item)
            return
        }
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
        // 翻页列表与浏览列表同源:同样按「显示隐藏 + 关键词 + 类型过滤」加工
        val visible = processEntries(
            state.entries, showHidden.value, queryOf(side).value, categoryOf(side).value, sortOf(side).value,
        )
        val items = if (kind == FileKind.IMAGE) {
            visible.filter { !it.isDir && FileKind.of(it.name) == FileKind.IMAGE }
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

    /** 本地其他类型:跳过查看器,直接弹系统「打开方式」选择器 */
    fun openLocalWithSystem(item: FileItem) {
        val dir = stateOf(Side.LEFT).path
        viewModelScope.launch {
            try {
                openWithSystem(context, File(dir, item.name))
            } catch (e: Exception) {
                events.emit("打开失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
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

    // ---- 全局搜索(拍板 2026-10-05,MT 同款裁剪:本地侧含 root 桥;结果页临时多选直接入队) ----

    private val _search = MutableStateFlow<LocalSearchState?>(null)
    val search: StateFlow<LocalSearchState?> = _search.asStateFlow()

    private val _searchSelection = MutableStateFlow<Set<String>>(emptySet())
    val searchSelection: StateFlow<Set<String>> = _searchSelection.asStateFlow()

    /** 全局搜索历史(拍板 2026-10-06,MT 同款:发起搜索即记,最近在前去重上限 20) */
    val searchHistory: StateFlow<List<String>> = settings.searchHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearSearchHistory() {
        viewModelScope.launch { settings.clearSearchHistory() }
    }

    private var searchJob: Job? = null

    /** 打开搜索面板:范围 = 打开时左栏所在目录(root 桥目录同样支持) */
    fun openSearch() {
        val left = _left.value
        _searchSelection.value = emptySet()
        _search.value = LocalSearchState(scopeDir = left.path, viaRoot = left.viaRoot)
    }

    fun updateSearchQuery(query: String) = editSearchInput { it.copy(query = query) }

    fun setSearchRecursive(recursive: Boolean) = editSearchInput { it.copy(recursive = recursive) }

    fun setSearchCategory(category: FileCategory) = editSearchInput { it.copy(category = category) }

    fun setSearchTimeRange(range: SearchTimeRange) = editSearchInput { it.copy(timeRange = range) }

    /** 仅输入阶段可改条件(结果阶段改条件语义混乱,须重新发起) */
    private fun editSearchInput(edit: (LocalSearchState) -> LocalSearchState) {
        _search.update { s -> if (s?.phase == LocalSearchState.Phase.INPUT) edit(s) else s }
    }

    fun runSearch() {
        val s = _search.value ?: return
        if (s.query.isBlank()) {
            viewModelScope.launch { events.emit("请输入搜索关键词") }
            return
        }
        searchJob?.cancel()
        _searchSelection.value = emptySet()
        _search.update { it?.copy(phase = LocalSearchState.Phase.RUNNING, scanned = 0, results = emptyList()) }
        settings.addSearchHistory(s.query.trim())
        searchJob = viewModelScope.launch {
            val hits = try {
                if (s.viaRoot) searchViaRoot(s) else searchViaWalk(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events.emit("搜索失败：${e.message ?: e.javaClass.simpleName}")
                _search.update { it?.copy(phase = LocalSearchState.Phase.DONE) }
                return@launch
            }
            _search.update {
                it?.copy(phase = LocalSearchState.Phase.DONE, results = hits, scanned = hits.size)
            }
        }
    }

    /** 停止:普通目录走增量扫描,停止即保留已扫出的部分;root find 为一次性命令,只能放弃整体等待 */
    fun stopSearch() {
        searchJob?.cancel()
        _search.update { it?.copy(phase = LocalSearchState.Phase.DONE) }
    }

    fun closeSearch() {
        searchJob?.cancel()
        _search.value = null
        _searchSelection.value = emptySet()
    }

    fun toggleSearchSelect(hit: SearchHit) {
        _searchSelection.update { set -> if (hit.path in set) set - hit.path else set + hit.path }
    }

    fun selectAllSearchResults() {
        _search.value?.let { _searchSelection.value = it.results.mapTo(mutableSetOf()) { h -> h.path } }
    }

    /** 点击单条 = 跳转定位:加载所在目录并选中该文件(浏览侧现有多选高亮即定位效果),关闭搜索页 */
    fun locateResult(hit: SearchHit) {
        closeSearch()
        load(Side.LEFT, hit.path.substringBeforeLast('/'))
        _selectedLeft.value = setOf(hit.name)
    }

    /** 结果直接入队(拍板:不动"多选不跨目录"状态机);rel 平铺文件名,目标语义与目录内上传一致 */
    fun uploadSearchResults(all: Boolean) {
        val s = _search.value ?: return
        val hits = if (all) s.results else s.results.filter { it.path in _searchSelection.value }
        if (hits.isEmpty()) {
            viewModelScope.launch { events.emit("没有可上传的文件") }
            return
        }
        viewModelScope.launch {
            val mode = settings.loadUploadModeOnce()
            val manualTarget = _right.value.path
            try {
                if (s.viaRoot) {
                    events.emit("正在通过 root 读取所选内容…")
                    val entries = hits.map {
                        RootEntry(path = it.path, name = it.name, isDir = false, size = it.size, mtimeMs = it.mtimeMs)
                    }
                    val n = uploadRepository.enqueueFromRoot(s.scopeDir, entries, mode, manualTarget)
                    if (n > 0) closeSearch()
                    events.emit(if (n > 0) "已加入队列：$n 个文件" else "没有可上传的文件")
                } else {
                    val tasks = hits.map { hit ->
                        val f = File(hit.path)
                        UploadTask(
                            file = f,
                            remoteDir = if (mode == UploadMode.AUTO_DATE) {
                                UploadPlanning.autoDirFor(f.lastModified())
                            } else {
                                UploadPlanning.normalizeDir(manualTarget)
                            },
                            rel = hit.name,
                        )
                    }
                    uploadRepository.enqueue(tasks)
                    closeSearch()
                    events.emit("已加入队列：${tasks.size} 个文件")
                }
            } catch (e: Exception) {
                events.emit(e.message ?: "读取失败")
            }
        }
    }

    /** 普通目录:walkTopDown 增量扫描,每 32 个文件回报一次进度并可取消(协程取消点) */
    private suspend fun searchViaWalk(s: LocalSearchState): List<SearchHit> = withContext(Dispatchers.IO) {
        val since = s.timeRange.sinceMs(System.currentTimeMillis())
        val root = File(s.scopeDir)
        val walk = if (s.recursive) root.walkTopDown() else root.walkTopDown().maxDepth(1)
        val out = mutableListOf<SearchHit>()
        var scanned = 0
        val iterator = walk.iterator()
        while (iterator.hasNext()) {
            val f = iterator.next()
            if (!f.isFile) continue
            scanned++
            if (scanned % 32 == 0) {
                _search.update { st -> st?.copy(scanned = scanned, results = out.toList()) }
                ensureActive()
            }
            if (s.category != FileCategory.ALL && FileCategory.of(f.name) != s.category) continue
            if (!NameMatching.matches(s.query, f.name)) continue
            val mtime = f.lastModified()
            if (since != null && mtime < since) continue
            out += SearchHit(f.absolutePath, f.name, f.length(), mtime)
        }
        out
    }

    /** root 桥目录:find 一次性出结果(无逐条进度,见 stopSearch 注);类型/时间过滤与普通路径同谓词 */
    private suspend fun searchViaRoot(s: LocalSearchState): List<SearchHit> {
        if (!rootShell.ensureAvailable()) throw IOException("root 不可用或未授权")
        val entries = rootShell.searchFiles(s.scopeDir, s.recursive, s.query)
        val since = s.timeRange.sinceMs(System.currentTimeMillis())
        return entries.asSequence()
            .filter { s.category == FileCategory.ALL || FileCategory.of(it.name) == s.category }
            .filter { since == null || it.mtimeMs >= since }
            .map { SearchHit(it.path, it.name, it.size, it.mtimeMs) }
            .toList()
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
        // 换目录即清空多选与目录内搜索词(拍板:不跨目录保留;本地/远程同规则)
        if (stateFlowOf(side).value.path != path) {
            if (side == Side.LEFT) {
                _selectedLeft.value = emptySet()
                _leftQuery.value = ""
            } else {
                _selectedRight.value = emptySet()
                _rightQuery.value = ""
            }
        }
        stateFlowOf(side).update { it.copy(path = path, loading = true, error = null) }
        viewModelScope.launch {
            try {
                when (side) {
                    Side.LEFT -> {
                        val (items, viaRoot) = listLocal(path)
                        stateFlowOf(side).update { it.copy(entries = items, viaRoot = viaRoot, loading = false) }
                    }

                    Side.RIGHT -> {
                        val entries = listRemote(path, refresh)
                        stateFlowOf(side).update { it.copy(entries = entries, loading = false) }
                    }
                }
            } catch (e: Exception) {
                stateFlowOf(side).update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    /** 本地列目录 + root 回落(拍板 2026-10-05):File.listFiles() 失败 → su 列取(Android/data 等受限目录) */
    private suspend fun listLocal(path: String): Pair<List<FileItem>, Boolean> = withContext(Dispatchers.IO) {
        val files = File(path).listFiles()
        if (files != null) {
            Pair(
                files.map { f ->
                    FileItem(
                        name = f.name,
                        isDir = f.isDirectory,
                        size = f.length(),
                        modifiedText = formatTime(f.lastModified()),
                        mtimeMs = f.lastModified(),
                    )
                }.sortedWith(ITEM_ORDER),
                false,
            )
        } else {
            Pair(listViaRoot(path), true)
        }
    }

    private suspend fun listViaRoot(path: String): List<FileItem> {
        if (!rootShell.ensureAvailable()) {
            throw IOException(
                if (path.contains("Android/data") || path.contains("Android/obb")) {
                    "Android 11+ 系统限制：普通应用（含「所有文件访问」权限）无法读取此目录；" +
                        "root 不可用或未授权\n可在原应用或其他文件管理器里选中文件「分享到杂物上传」"
                } else {
                    "无法读取该目录（系统目录或权限不足；root 不可用或未授权）"
                },
            )
        }
        return try {
            rootShell.list(path).map { e ->
                FileItem(
                    name = e.name,
                    isDir = e.isDir,
                    size = e.size,
                    modifiedText = formatTime(e.mtimeMs),
                    mtimeMs = e.mtimeMs,
                )
            }.sortedWith(ITEM_ORDER)
        } catch (e: Exception) {
            throw IOException("root 列目录失败：${e.message}")
        }
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms))

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
