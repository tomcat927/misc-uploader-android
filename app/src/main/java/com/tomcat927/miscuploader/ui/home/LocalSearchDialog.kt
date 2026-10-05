package com.tomcat927.miscuploader.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tomcat927.miscuploader.ui.home.LocalSearchState.Phase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局搜索面板(拍板 2026-10-05,MT 同款裁剪):输入(关键词/递归/类型/时间) → 运行(计数+停止) →
 * 结果(临时多选直接入队;点行=跳转定位)。仅本地侧(含 root 桥)。
 */
@Composable
fun LocalSearchDialog(
    state: LocalSearchState,
    selection: Set<String>,
    history: List<String>,
    onQuery: (String) -> Unit,
    onRecursive: (Boolean) -> Unit,
    onCategory: (FileCategory) -> Unit,
    onTime: (SearchTimeRange) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onToggleSelect: (SearchHit) -> Unit,
    onSelectAll: () -> Unit,
    onLocate: (SearchHit) -> Unit,
    onUpload: (all: Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onClose: () -> Unit,
) {
    var showHistory by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                // 标题行:标题 + 范围 + (输入阶段)历史入口 + 关闭
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (showHistory) "历史记录" else "搜索文件",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            state.scopeDir + if (state.viaRoot) "（root）" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.phase == Phase.INPUT) {
                        FilledTonalIconButton(
                            onClick = { showHistory = !showHistory },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = "搜索历史",
                                modifier = Modifier.size(17.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    TextButton(onClick = onClose) { Text("关闭") }
                }

                when (state.phase) {
                    Phase.INPUT ->
                        if (showHistory) {
                            SearchHistoryPanel(
                                history = history,
                                onPick = { term ->
                                    onQuery(term)
                                    showHistory = false
                                },
                                onClear = onClearHistory,
                                onBack = { showHistory = false },
                            )
                        } else {
                            SearchInputPanel(
                                state, onQuery, onRecursive, onCategory, onTime, onRun, onClose,
                            )
                        }

                    Phase.RUNNING -> SearchRunningPanel(state, onStop)

                    Phase.DONE -> SearchResultsPanel(
                        state, selection, onToggleSelect, onSelectAll, onLocate, onUpload,
                    )
                }
            }
        }
    }
}

/** 搜索历史(MT 同款):点条目回填关键词(不自动开搜),底部清空/返回 */
@Composable
private fun SearchHistoryPanel(
    history: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    if (history.isEmpty()) {
        Text(
            "暂无历史记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        )
    } else {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(history, key = { it }) { term ->
                Text(
                    term,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(term) }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClear, enabled = history.isNotEmpty()) { Text("清空") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onBack) { Text("返回") }
    }
}

@Composable
private fun SearchInputPanel(
    state: LocalSearchState,
    onQuery: (String) -> Unit,
    onRecursive: (Boolean) -> Unit,
    onCategory: (FileCategory) -> Unit,
    onTime: (SearchTimeRange) -> Unit,
    onRun: () -> Unit,
    onClose: () -> Unit,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQuery,
        singleLine = true,
        label = { Text("关键词（支持 * ? 通配符）") },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text("含子目录", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(4.dp))
        Switch(checked = state.recursive, onCheckedChange = onRecursive)
        Spacer(Modifier.weight(1f))
        SearchTimeRange.entries.forEach { range ->
            FilterChip(
                selected = state.timeRange == range,
                onClick = { onTime(range) },
                label = { Text(range.label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FileCategory.entries.forEach { c ->
            FilterChip(
                selected = state.category == c,
                onClick = { onCategory(c) },
                label = { Text(c.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onClose) { Text("取消") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onRun, enabled = state.query.isNotBlank()) { Text("开始搜索") }
    }
}

@Composable
private fun SearchRunningPanel(state: LocalSearchState, onStop: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            "已扫描 ${state.scanned} 个文件，找到 ${state.results.size} 个",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        TextButton(onClick = onStop, modifier = Modifier.padding(top = 4.dp)) { Text("停止") }
    }
}

@Composable
private fun SearchResultsPanel(
    state: LocalSearchState,
    selection: Set<String>,
    onToggleSelect: (SearchHit) -> Unit,
    onSelectAll: () -> Unit,
    onLocate: (SearchHit) -> Unit,
    onUpload: (all: Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (state.results.isEmpty()) {
            Text(
                "没有匹配的文件",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 24.dp).align(Alignment.CenterHorizontally),
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "匹配 ${state.results.size} 项（点行跳转定位，勾选后可批量上传）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("全选")
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(state.results, key = { it.path }) { hit ->
                SearchHitRow(
                    hit = hit,
                    selected = hit.path in selection,
                    onToggleSelect = { onToggleSelect(hit) },
                    onLocate = { onLocate(hit) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onUpload(true) }) { Text("全部上传") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onUpload(false) }, enabled = selection.isNotEmpty()) {
                Text("上传所选 ${selection.size}")
            }
        }
    }
}

@Composable
private fun SearchHitRow(
    hit: SearchHit,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onLocate: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onLocate),
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        Column(Modifier.weight(1f)) {
            Text(
                hit.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hit.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${formatBytesCompact(hit.size)} · ${formatTimeCompact(hit.mtimeMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private fun formatBytesCompact(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024f)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024f / 1024f)
    else -> String.format(Locale.ROOT, "%.2f GB", bytes / 1024f / 1024f / 1024f)
}

private fun formatTimeCompact(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ms))
