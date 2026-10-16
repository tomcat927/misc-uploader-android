package com.tomcat927.miscuploader.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tomcat927.miscuploader.core.AiMessage
import com.tomcat927.miscuploader.data.db.AiConversationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 助手(拍板 2026-10-16):多会话 + 会话内连续对话;只读工具自动,动作工具需确认。
 */
@Composable
fun AiScreen(
    onOpenSettings: () -> Unit = {},
    viewModel: AiAgentViewModel = viewModel(),
) {
    val enabled by viewModel.aiEnabled.collectAsState()
    val activeId by viewModel.activeConversationId.collectAsState()
    val conversations by viewModel.conversations.collectAsState()

    when {
        !enabled -> NotEnabledScreen(onOpenSettings)
        activeId == null -> ConversationListScreen(
            conversations = conversations,
            onNew = viewModel::newConversation,
            onOpen = viewModel::openConversation,
            onDelete = viewModel::deleteConversation,
        )

        else -> ChatScreen(
            title = conversations.firstOrNull { it.id == activeId }?.title ?: "AI 助手",
            viewModel = viewModel,
            onBack = viewModel::backToList,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotEnabledScreen(onOpenSettings: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("AI 助手") }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AI 助手未启用", style = MaterialTheme.typography.titleMedium)
                Text(
                    "到「设置 → AI 助手」填写接口地址、密钥并选择模型后即可在此对话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenSettings) { Text("去设置") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationListScreen(
    conversations: List<AiConversationEntity>,
    onNew: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("AI 助手") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "新建会话")
            }
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "还没有会话，点右下角「+」新建一个，让 AI 帮你整理手机文件。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(conversations) { conv ->
                    ConversationRow(conv, onOpen = { onOpen(conv.id) }, onDelete = { onDelete(conv.id) })
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conv: AiConversationEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(conv.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatTime(conv.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = "删除会话")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(title: String, viewModel: AiAgentViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.messages.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearConversation) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "清空会话")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(Modifier.size(4.dp)) }
                if (state.messages.isEmpty()) item { EmptyHint() }
                items(state.messages) { msg -> MessageRow(msg) }
                if (state.busy) item { BusyRow(state.stage) }
                state.error?.let { err -> item { ErrorCard(err, viewModel::dismissError) } }
                item { Spacer(Modifier.size(8.dp)) }
            }

            state.pendingAction?.let { pa ->
                ConfirmCard(pa, viewModel::approveAction, viewModel::rejectAction)
            }

            InputBar(
                value = input,
                busy = state.busy,
                sendEnabled = state.pendingAction == null,
                onValueChange = { input = it },
                onSend = {
                    viewModel.send(input)
                    input = ""
                },
                onCancel = viewModel::cancel,
            )
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("让 AI 帮你整理手机文件", style = MaterialTheme.typography.titleSmall)
        Text(
            "例如：\n「看看下载目录有什么，建议哪些传到仓库」\n「把截图归档到合适的目录并清理本地」",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageRow(msg: AiMessage) {
    when {
        msg.role == "tool" -> ToolChip("↳ " + (msg.text ?: ""))
        msg.role == "assistant" && msg.toolCalls.isNotEmpty() -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                msg.toolCalls.forEach { tc ->
                    ToolChip("🔧 ${tc.name}(${tc.arguments.take(160)})")
                }
                if (!msg.text.isNullOrBlank()) Bubble(msg.text!!, fromUser = false)
            }
        }

        msg.role == "user" && msg.images.isNotEmpty() -> ToolChip("🖼 已读取 ${msg.images.size} 张图片")
        msg.role == "user" -> Bubble(msg.text.orEmpty(), fromUser = true)
        else -> if (!msg.text.isNullOrBlank()) Bubble(msg.text!!, fromUser = false)
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (fromUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Text(text, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ToolChip(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BusyRow(stage: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(stage ?: "处理中…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun ConfirmCard(action: PendingAction, onApprove: () -> Unit, onReject: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("需要你确认：${action.title}", style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                action.lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove) { Text("执行") }
                TextButton(onClick = onReject) { Text("跳过") }
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    busy: Boolean,
    sendEnabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("描述你想怎么整理…") },
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                TextButton(onClick = onCancel) { Text("停止") }
            } else {
                IconButton(onClick = onSend, enabled = sendEnabled && value.isNotBlank()) {
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))
