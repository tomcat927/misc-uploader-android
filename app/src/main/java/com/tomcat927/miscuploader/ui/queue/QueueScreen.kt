package com.tomcat927.miscuploader.ui.queue

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tomcat927.miscuploader.data.ConnectionManager
import com.tomcat927.miscuploader.data.UploadRepository
import com.tomcat927.miscuploader.data.db.HistoryEntity
import com.tomcat927.miscuploader.data.db.UploadItemEntity
import com.tomcat927.miscuploader.data.db.UploadState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 归档状态数据(C):misc-sync.py 每轮写 /opt/misc/.sync/state.json(app 视角 /.sync/state.json) */
data class ArchiveState(
    val lastRunAt: Long?,
    val pendingCount: Int?,
    val vaultTotal: Int?,
)

data class ArchiveUiState(
    val loading: Boolean = false,
    val unavailable: Boolean = false,
    val state: ArchiveState? = null,
)

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val repository: UploadRepository,
    private val connection: ConnectionManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** 队列暂停(拍板 2026-10-05):持久化,杀进程重启仍保持 */
    val queuePaused: StateFlow<Boolean> = repository.queuePaused

    fun togglePause() = repository.setQueuePaused(!repository.queuePaused.value)

    /**
     * 拍板(对齐桌面端):进行中(pending/hash/uploading/cooldown)按入队序置顶;
     * 已完成(done/failed/skipped)按完成时间最新在前。
     */
    val items: StateFlow<List<UploadItemEntity>> = repository.items
        .map { list ->
            val (inFlight, finished) = list.partition { it.state !in UploadState.FINISHED }
            inFlight.sortedBy { it.enqueuedAt } + finished.sortedByDescending { it.finishedAt ?: it.enqueuedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 上传历史(A1):sha→最新落点,展示最近 500 条 */
    val history: StateFlow<List<HistoryEntity>> = repository.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _archive = MutableStateFlow(ArchiveUiState())
    val archive: StateFlow<ArchiveUiState> = _archive.asStateFlow()

    init {
        loadArchiveState()
    }

    /** 读服务器归档状态(C):下载 /.sync/state.json 解析;未连接/未部署/解析失败 = unavailable */
    fun loadArchiveState() {
        viewModelScope.launch {
            _archive.value = ArchiveUiState(loading = true)
            try {
                val client = connection.clientOrNull()
                    ?: throw IllegalStateException("未连接")
                val f = File(context.cacheDir, "archive_state.json")
                client.downloadTo(ARCHIVE_STATE_PATH, f)
                val json = JSONObject(f.readText())
                f.delete()
                _archive.value = ArchiveUiState(
                    state = ArchiveState(
                        lastRunAt = json.optLong("last_run_at").takeIf { it > 0 },
                        pendingCount = json.optInt("pending_count", -1).takeIf { it >= 0 },
                        vaultTotal = json.optInt("vault_total", -1).takeIf { it >= 0 },
                    ),
                )
            } catch (e: Exception) {
                _archive.value = ArchiveUiState(unavailable = true)
            }
        }
    }

    fun retry(id: Long) = repository.retry(id)

    fun retryAllFailed() = repository.retryAllFailed()

    fun clearFinished() = repository.clearFinished()

    companion object {
        /** 服务器实写 /opt/misc/.sync/state.json;app 账号 base_path=/misc,chroot 后即此路径(拍板 2026-10-05) */
        const val ARCHIVE_STATE_PATH = "/.sync/state.json"
    }
}

/** 队列页视图(A1):进行中队列 / 上传历史 */
private enum class QueueView(val label: String) {
    QUEUE("队列"),
    HISTORY("历史"),
}

@Composable
fun QueueScreen(viewModel: QueueViewModel = viewModel()) {
    val items by viewModel.items.collectAsState()
    val history by viewModel.history.collectAsState()
    val archive by viewModel.archive.collectAsState()
    var view by rememberSaveable { mutableStateOf(QueueView.QUEUE) }

    // 进页/切回队列 tab 自动刷新归档状态
    LaunchedEffect(Unit) {
        viewModel.loadArchiveState()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (view == QueueView.QUEUE) "上传队列" else "上传历史",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = view == QueueView.QUEUE,
                onClick = { view = QueueView.QUEUE },
                label = { Text(QueueView.QUEUE.label) },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = view == QueueView.HISTORY,
                onClick = { view = QueueView.HISTORY },
                label = { Text(QueueView.HISTORY.label) },
            )
        }
        HorizontalDivider()

        ArchiveStateCard(
            state = archive,
            onRefresh = viewModel::loadArchiveState,
        )

        when (view) {
            QueueView.QUEUE -> QueueList(
                items = items,
                paused = queuePaused,
                onTogglePause = viewModel::togglePause,
                onRetryAllFailed = viewModel::retryAllFailed,
                onClearFinished = viewModel::clearFinished,
                onRetry = viewModel::retry,
            )

            QueueView.HISTORY -> HistoryList(history)
        }
    }
}

/** 归档状态卡(C):数据/读取中/未部署三态;数据源 = misc-sync.py 每轮写的 /.sync/state.json */
@Composable
private fun ArchiveStateCard(state: ArchiveUiState, onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("归档状态", style = MaterialTheme.typography.labelLarge)
                Text(
                    when {
                        state.loading -> "读取中…"
                        state.state != null -> buildString {
                            state.state.pendingCount?.let { append("待归档 $it 项") }
                            state.state.vaultTotal?.let {
                                if (isNotEmpty()) append(" · ")
                                append("冷层已有 $it 项")
                            }
                            state.state.lastRunAt?.let {
                                if (isNotEmpty()) append(" · ")
                                append(ARCHIVE_DATE.format(Date(it * 1000)))
                            }
                        }.ifEmpty { "已就绪" }

                        else -> "未获取到（未连接或服务器脚本未部署）"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = onRefresh, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新归档状态", modifier = Modifier.size(17.dp))
            }
        }
    }
}

private val ARCHIVE_DATE = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

@Composable
private fun QueueList(
    items: List<UploadItemEntity>,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onRetryAllFailed: () -> Unit,
    onClearFinished: () -> Unit,
    onRetry: (Long) -> Unit,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "队列为空\n在「文件」页长按文件进入多选，点「上传」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onTogglePause) {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (paused) "继续" else "暂停")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRetryAllFailed, enabled = items.any { it.state == UploadState.FAILED }) {
                Text("重试全部失败")
            }
            TextButton(onClick = onClearFinished, enabled = items.any { it.state in UploadState.FINISHED }) {
                Text("清除已完成")
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.id }) { item ->
                QueueRow(
                    item = item,
                    onRetry = { onRetry(item.id) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun HistoryList(history: List<HistoryEntity>) {
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂无上传历史\n上传成功的内容会记录在这里（同内容再次上传将被跳过）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(history, key = { it.id }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Done,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        entry.remotePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    HISTORY_DATE.format(Date(entry.uploadedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

private val HISTORY_DATE = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

@Composable
private fun QueueRow(item: UploadItemEntity, onRetry: () -> Unit) {
    val accent = when (item.state) {
        UploadState.DONE -> MaterialTheme.colorScheme.primary
        UploadState.FAILED -> MaterialTheme.colorScheme.error
        UploadState.UPLOADING, UploadState.PENDING, UploadState.COOLDOWN, UploadState.HASHING ->
            MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (item.state) {
                    UploadState.DONE -> Icons.Filled.Done
                    UploadState.FAILED -> Icons.Filled.ErrorOutline
                    UploadState.SKIPPED -> Icons.Filled.RemoveCircleOutline
                    UploadState.COOLDOWN -> Icons.Filled.Schedule
                    else -> Icons.Filled.CloudUpload
                },
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    item.remotePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stateLabel(item.state),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
            if (item.state == UploadState.FAILED) {
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }

        if (item.state == UploadState.UPLOADING) {
            LinearProgressIndicator(
                progress = { item.progress / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
            )
        }
        item.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = if (item.state == UploadState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun stateLabel(state: String): String = when (state) {
    UploadState.PENDING -> "待上传"
    UploadState.HASHING -> "校验中"
    UploadState.UPLOADING -> "上传中"
    UploadState.COOLDOWN -> "等待重试"
    UploadState.DONE -> "已完成"
    UploadState.FAILED -> "失败"
    UploadState.SKIPPED -> "已跳过"
    else -> state
}
