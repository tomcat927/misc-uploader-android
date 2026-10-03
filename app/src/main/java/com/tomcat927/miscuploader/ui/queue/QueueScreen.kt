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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomcat927.miscuploader.data.UploadRepository
import com.tomcat927.miscuploader.data.db.UploadItemEntity
import com.tomcat927.miscuploader.data.db.UploadState
import dagger.hilt.android.lifecycle.HiltViewModel
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
     * 拍板(对齐桌面端):进行中(pending/uploading/cooldown)按入队序置顶;
     * 已完成(done/failed/skipped)按完成时间最新在前。
     */
    val items: StateFlow<List<UploadItemEntity>> = repository.items
        .map { list ->
            val (inFlight, finished) = list.partition { it.state in UploadState.FINISHED }
            inFlight.sortedBy { it.enqueuedAt } + finished.sortedByDescending { it.finishedAt ?: it.enqueuedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun retry(id: Long) = repository.retry(id)

    fun retryAllFailed() = repository.retryAllFailed()

    fun clearFinished() = repository.clearFinished()
}

@Composable
fun QueueScreen(viewModel: QueueViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("上传队列", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = viewModel::retryAllFailed, enabled = items.any { it.state == UploadState.FAILED }) {
                Text("重试全部失败")
            }
            TextButton(onClick = viewModel::clearFinished, enabled = items.any { it.state in UploadState.FINISHED }) {
                Text("清除已完成")
            }
        }
        HorizontalDivider()

        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "队列为空\n在「文件」页长按文件进入多选，点「上传」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(items, key = { it.id }) { item ->
                    QueueRow(
                        item = item,
                        onRetry = { viewModel.retry(item.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun QueueRow(item: UploadItemEntity, onRetry: () -> Unit) {
    val accent = when (item.state) {
        UploadState.DONE -> MaterialTheme.colorScheme.primary
        UploadState.FAILED -> MaterialTheme.colorScheme.error
        UploadState.UPLOADING, UploadState.PENDING, UploadState.COOLDOWN -> MaterialTheme.colorScheme.tertiary
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
    UploadState.UPLOADING -> "上传中"
    UploadState.COOLDOWN -> "等待重试"
    UploadState.DONE -> "已完成"
    UploadState.FAILED -> "失败"
    UploadState.SKIPPED -> "已跳过"
    else -> state
}
