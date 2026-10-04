package com.tomcat927.miscuploader.ui.queue

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
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.tomcat927.miscuploader.data.UploadRepository
import com.tomcat927.miscuploader.data.db.HistoryEntity
import com.tomcat927.miscuploader.data.db.UploadItemEntity
import com.tomcat927.miscuploader.data.db.UploadState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val repository: UploadRepository,
) : ViewModel() {

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

    fun retry(id: Long) = repository.retry(id)

    fun retryAllFailed() = repository.retryAllFailed()

    fun clearFinished() = repository.clearFinished()
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
    var view by rememberSaveable { mutableStateOf(QueueView.QUEUE) }

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

        when (view) {
            QueueView.QUEUE -> QueueList(
                items = items,
                onRetryAllFailed = viewModel::retryAllFailed,
                onClearFinished = viewModel::clearFinished,
                onRetry = viewModel::retry,
            )

            QueueView.HISTORY -> HistoryList(history)
        }
    }
}

@Composable
private fun QueueList(
    items: List<UploadItemEntity>,
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
        ) {
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
