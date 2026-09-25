package com.qy.cryptoassistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AiPurple = Color(0xFF7132F5)
private val AiInk = Color(0xFF17151D)
private val AiMuted = Color(0xFF686B82)
private val AiNegative = Color(0xFFBC3642)
private const val CHAT_DEFAULT_PROMPT = "保持清晰、谨慎、适合新手的语气；先讲事实，再讲风险。普通聊天不主动使用用户持仓。"

private fun aiTimeLabel(time: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(time))

@Composable
fun AiWorkspaceScreen(
    modifier: Modifier,
    appContext: Context,
    provider: String,
    configured: Boolean,
    holdings: List<BinanceHolding>?,
    market: MarketSnapshot?,
    prefs: android.content.SharedPreferences,
    openAiSettings: () -> Unit,
    openBinanceSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(ChatStore.load(appContext)) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var streamingJob by remember { mutableStateOf<Job?>(null) }
    var streamingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var newDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var generating by remember { mutableStateOf(false) }

    fun persist(next: List<ChatSession>) {
        sessions = next.sortedByDescending { it.updatedAt }
        ChatStore.save(appContext, sessions)
    }

    fun current(): ChatSession? = sessions.firstOrNull { it.id == selectedId }

    fun createSession(title: String, kind: String = "chat", context: String = ""): ChatSession {
        val session = ChatSession(
            title = title.ifBlank { "新会话" },
            kind = kind,
            tonePreset = prefs.getString("ai_tone_preset", "默认") ?: "默认",
            analysisPrompt = prefs.getString("ai_analysis_prompt", CHAT_DEFAULT_PROMPT) ?: CHAT_DEFAULT_PROMPT,
            advicePrompt = prefs.getString("ai_advice_prompt", CHAT_DEFAULT_PROMPT) ?: CHAT_DEFAULT_PROMPT,
            context = context,
        )
        persist(listOf(session) + sessions)
        selectedId = session.id
        return session
    }

    LaunchedEffect(sessions.size) {
        if (selectedId == null || sessions.none { it.id == selectedId }) selectedId = sessions.firstOrNull()?.id
    }

    fun updateSession(session: ChatSession) = persist(sessions.map { if (it.id == session.id) session else it })

    fun stopGeneration() {
        streamingJob?.cancel()
        streamingJob = null
        streamingId = null
        generating = false
    }

    fun sendMessage(text: String, retryMessage: ChatMessage? = null) {
        val base = current() ?: createSession("新会话")
        val retryIndex = retryMessage?.let { message -> base.messages.indexOfFirst { it.id == message.id } } ?: -1
        val retryPrompt = retryIndex.takeIf { it > 0 }?.let { index ->
            base.messages.getOrNull(index - 1)?.takeIf { it.role == "user" }?.content
        }
        val prompt = text.trim().ifBlank { retryPrompt.orEmpty() }
        if ((prompt.isBlank() && retryMessage == null) || streamingJob != null) return
        val userMessage = ChatMessage(role = "user", content = prompt)
        val oldMessages = if (retryIndex > 0) base.messages.take(retryIndex - 1) + userMessage else base.messages + userMessage
        val assistant = ChatMessage(role = "assistant", content = "")
        val prepared = base.copy(messages = oldMessages + assistant, updatedAt = System.currentTimeMillis())
        updateSession(prepared)
        input = ""
        error = null
        streamingId = assistant.id
        generating = true
        val key = SecretStore.read(appContext, "ai_key")
        val model = SecretStore.read(appContext, "ai_model")
        if (key.isNullOrBlank() || model.isNullOrBlank()) {
            val failed = prepared.copy(messages = prepared.messages.map { if (it.id == assistant.id) it.copy(content = "请先配置 AI API。", error = true) else it })
            updateSession(failed); streamingId = null; generating = false; return
        }
        val systemPrompt = when (base.kind) {
            "analysis" -> "你是加密资产风险解释助手。只根据会话中提供的数据回答，不预测确定价格，不执行买卖。${base.analysisPrompt}\n本会话数据上下文：${base.context}"
            "advice" -> "你是谨慎的加密资产研究助手。建议只用于研究，不构成投资建议。${base.advicePrompt}\n本会话数据上下文：${base.context}"
            else -> "你是 NoobCoin 的加密资产科普助手。普通聊天不主动使用用户持仓；若用户要求分析，先提醒需要提供数据。${base.analysisPrompt.ifBlank { CHAT_DEFAULT_PROMPT }}"
        }
        val apiMessages = buildList {
            add(AiApi.Message("system", systemPrompt))
            prepared.messages.filter { it.id != assistant.id && !it.error }.forEach { add(AiApi.Message(it.role, it.content)) }
        }
        streamingJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AiApi.streamChat(provider, key, model, apiMessages) { delta ->
                        val updated = sessions.firstOrNull { it.id == base.id } ?: prepared
                        val changed = updated.copy(messages = updated.messages.map { if (it.id == assistant.id) it.copy(content = it.content + delta) else it }, updatedAt = System.currentTimeMillis())
                        scope.launch(Dispatchers.Main.immediate) { updateSession(changed) }
                    }
                }
            } catch (cancelled: CancellationException) {
                // Stop generation keeps the partial assistant text as a resumable local message.
            } catch (e: Exception) {
                val latest = sessions.firstOrNull { it.id == base.id } ?: prepared
                updateSession(latest.copy(messages = latest.messages.map { if (it.id == assistant.id) it.copy(content = "生成失败：${e.message ?: "请求失败"}", error = true) else it }))
                error = e.message ?: "请求失败，请重试。"
            } finally {
                streamingJob = null; streamingId = null; generating = false
            }
        }
    }

    fun generateAnalysis() {
        val data = holdings ?: return
        if (data.isEmpty() || data.any { it.valueUsdt == null }) return
        if (!configured) return
        generating = true; error = null
        val total = data.mapNotNull { it.valueUsdt }.sum().coerceAtLeast(0.00000001)
        val holdingsContext = data.mapNotNull { holding -> holding.valueUsdt?.let { value -> "${holding.asset} ${String.format(Locale.US, "%.2f", value / total * 100)}%" } }.joinToString(", ")
        val session = createSession("我的组合 · ${aiTimeLabel(System.currentTimeMillis())}", "analysis", "已估值持仓占比：$holdingsContext")
        scope.launch {
            try {
                val key = SecretStore.read(appContext, "ai_key") ?: error("请先配置 AI API。")
                val model = SecretStore.read(appContext, "ai_model") ?: error("请先选择 AI 模型。")
                val result = withContext(Dispatchers.IO) { AiApi.analyze(provider, key, model, data, session.analysisPrompt) }
                updateSession(session.copy(messages = listOf(
                    ChatMessage(role = "user", content = "请分析我的真实持仓集中度、稳定币比例和需要关注的风险。"),
                    ChatMessage(role = "assistant", content = result),
                ), updatedAt = System.currentTimeMillis()))
            } catch (e: Exception) { error = e.message ?: "分析失败，请重试。" }
            finally { generating = false }
        }
    }

    fun generateAdvice() {
        val data = holdings ?: return
        if (data.isEmpty() || !configured || market == null) return
        generating = true; error = null
        val context = market.tickers.filter { ticker -> data.any { it.asset == ticker.symbol } || ticker.symbol in setOf("BTC", "ETH") }
            .joinToString("；") { "${it.symbol} 价格 ${it.priceUsdt}，24h ${String.format(Locale.US, "%+.2f", it.changePercent)}%" }
        val session = createSession("持仓建议 · ${aiTimeLabel(System.currentTimeMillis())}", "advice", "生成时行情摘要：$context；新闻已在首次请求时读取")
        scope.launch {
            try {
                val key = SecretStore.read(appContext, "ai_key") ?: error("请先配置 AI API。")
                val model = SecretStore.read(appContext, "ai_model") ?: error("请先选择 AI 模型。")
                val news = withContext(Dispatchers.IO) { runCatching { NewsApi.fetchLatest() }.getOrDefault(emptyList()) }
                val result = withContext(Dispatchers.IO) { AiApi.advice(provider, key, model, data, market.tickers, news, session.advicePrompt) }
                updateSession(session.copy(messages = listOf(
                    ChatMessage(role = "user", content = "结合实时行情和公开新闻，给出未来 7 天的风险提示型持仓建议。"),
                    ChatMessage(role = "assistant", content = result),
                ), updatedAt = System.currentTimeMillis()))
            } catch (e: Exception) { error = e.message ?: "建议生成失败，请重试。" }
            finally { generating = false }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = AiPurple, onPrimary = Color.White, background = Color(0xFFF8F7FC), surface = Color.White, onSurface = AiInk)) {
        Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI 助手", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { newDialog = true }) { Icon(Icons.Outlined.AddComment, "新建会话", tint = AiPurple) }
            }
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(tab == 0, { tab = 0 }, text = { Text("聊天") })
                Tab(tab == 1, { tab = 1 }, text = { Text("分析持仓") })
                Tab(tab == 2, { tab = 2 }, text = { Text("持仓建议") })
            }
            SessionStrip(sessions, selectedId, { selectedId = it }, { renameTarget = it }, { pendingDelete = it })
            val selected = current()
            when (tab) {
                0 -> ChatConversation(selected, input, { input = it }, generating, error, { sendMessage(input) }, ::stopGeneration, { openAiSettings() }, { message ->
                    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("NoobCoin AI", message.content))
                }, { message -> sendMessage(message.content, message) }, modifier.weight(1f))
                1 -> AnalysisTab(selected, holdings, configured, generating, error, ::generateAnalysis, openAiSettings, openBinanceSettings, { tab = 0 }, input, { input = it }, { sendMessage(input) }, ::stopGeneration, { message ->
                    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("NoobCoin AI", message.content))
                }, { message -> sendMessage(message.content, message) }, modifier.weight(1f))
                else -> AdviceTab(selected, holdings, market, configured, generating, error, ::generateAdvice, openAiSettings, openBinanceSettings, { tab = 0 }, input, { input = it }, { sendMessage(input) }, ::stopGeneration, { message ->
                    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("NoobCoin AI", message.content))
                }, { message -> sendMessage(message.content, message) }, modifier.weight(1f))
            }
        }
    }
    if (newDialog) SessionNameDialog("新建会话", "新会话", { newDialog = false }, { createSession(it); newDialog = false })
    renameTarget?.let { target -> SessionNameDialog("重命名会话", target.title, { renameTarget = null }, { updateSession(target.copy(title = it.ifBlank { target.title }, updatedAt = System.currentTimeMillis())); renameTarget = null }) }
    pendingDelete?.let { target ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除会话？") }, text = { Text("将同时删除本机保存的消息，无法恢复。") },
            confirmButton = { TextButton({ persist(sessions.filterNot { it.id == target.id }); if (selectedId == target.id) selectedId = sessions.firstOrNull { it.id != target.id }?.id; pendingDelete = null }) { Text("删除", color = AiNegative) } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("取消") } })
    }
}

@Composable private fun SessionStrip(sessions: List<ChatSession>, selectedId: String?, select: (String) -> Unit, rename: (ChatSession) -> Unit, delete: (ChatSession) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        sessions.forEach { session ->
            Row(Modifier.padding(end = 6.dp).background(if (session.id == selectedId) Color(0xFFEDE6FF) else Color.White, RoundedCornerShape(18.dp)).clickable { select(session.id) }.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(session.title, color = if (session.id == selectedId) AiPurple else AiInk, maxLines = 1)
                var expanded by remember(session.id) { mutableStateOf(false) }
                IconButton({ expanded = true }, Modifier.size(30.dp)) { Icon(Icons.Outlined.MoreVert, "会话菜单", tint = AiMuted) }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { expanded = false; rename(session) })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { expanded = false; delete(session) })
                }
            }
        }
    }
}

@Composable private fun ChatConversation(session: ChatSession?, input: String, change: (String) -> Unit, generating: Boolean, error: String?, send: () -> Unit, stop: () -> Unit, settings: () -> Unit, copy: (ChatMessage) -> Unit, retry: (ChatMessage) -> Unit, modifier: Modifier) {
    if (session == null) return EmptyAiState("还没有会话", "点击右上角新建一个本地会话。", settings, modifier)
    Column(modifier) {
        MessageList(session.messages, copy, retry, Modifier.weight(1f))
        error?.let { Text(it, color = AiNegative, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)) }
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(input, change, Modifier.weight(1f), minLines = 1, maxLines = 5, placeholder = { Text("问问市场、概念或风险…") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
            Spacer(Modifier.width(8.dp))
            if (generating) IconButton(stop) { Icon(Icons.Outlined.StopCircle, "停止生成", tint = AiNegative) }
            else IconButton(send, enabled = input.isNotBlank()) { Icon(Icons.Outlined.Send, "发送", tint = AiPurple) }
        }
        Text("普通聊天默认不发送持仓；消息会保存到本机，但发送给 AI 的内容会离开设备。", color = AiMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable private fun AnalysisTab(session: ChatSession?, holdings: List<BinanceHolding>?, configured: Boolean, generating: Boolean, error: String?, generate: () -> Unit, settings: () -> Unit, binance: () -> Unit, openChat: () -> Unit, input: String, changeInput: (String) -> Unit, send: () -> Unit, stop: () -> Unit, copy: (ChatMessage) -> Unit, retry: (ChatMessage) -> Unit, modifier: Modifier) {
    Column(modifier) {
        if (session?.kind == "analysis") MessageList(session.messages, copy, retry, Modifier.weight(1f))
        else Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("把真实持仓摘要交给 AI，分析集中度、稳定币比例和风险。", color = AiMuted)
            when { holdings == null -> EmptyAiState("尚无真实持仓", "先连接 Binance 只读账户。", binance); !configured -> EmptyAiState("尚未配置 AI", "配置 DeepSeek / Kimi API。", settings); holdings.isEmpty() -> Text("现货账户暂无非零余额。", color = AiMuted); else -> Button(generate, enabled = !generating) { Text(if (generating) "正在分析…" else "开始分析") } }
        }
        if (session?.kind == "analysis") FollowupComposer(input, changeInput, generating, send, stop, openChat)
        error?.let { Text(it, color = AiNegative, fontSize = 12.sp) }
    }
}

@Composable private fun AdviceTab(session: ChatSession?, holdings: List<BinanceHolding>?, market: MarketSnapshot?, configured: Boolean, generating: Boolean, error: String?, generate: () -> Unit, settings: () -> Unit, binance: () -> Unit, openChat: () -> Unit, input: String, changeInput: (String) -> Unit, send: () -> Unit, stop: () -> Unit, copy: (ChatMessage) -> Unit, retry: (ChatMessage) -> Unit, modifier: Modifier) {
    Column(modifier) {
        if (session?.kind == "advice") MessageList(session.messages, copy, retry, Modifier.weight(1f))
        else Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("结合实时行情和公开新闻，生成未来 7 天的风险提示型建议。", color = AiMuted)
            when { holdings == null -> EmptyAiState("尚无真实持仓", "先连接 Binance 只读账户。", binance); !configured -> EmptyAiState("尚未配置 AI", "配置 DeepSeek / Kimi API。", settings); market == null -> Text("正在获取实时行情…", color = AiMuted); holdings.isEmpty() -> Text("现货账户暂无非零余额。", color = AiMuted); else -> Button(generate, enabled = !generating) { Text(if (generating) "正在获取行情与新闻…" else "生成持仓建议") } }
        }
        if (session?.kind == "advice") FollowupComposer(input, changeInput, generating, send, stop, openChat)
        error?.let { Text(it, color = AiNegative, fontSize = 12.sp) }
    }
}

@Composable private fun FollowupComposer(input: String, changeInput: (String) -> Unit, generating: Boolean, send: () -> Unit, stop: () -> Unit, openChat: () -> Unit) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedTextField(input, changeInput, Modifier.weight(1f), maxLines = 4, placeholder = { Text("继续追问这份结果…") })
        Spacer(Modifier.width(6.dp))
        if (generating) IconButton(stop) { Icon(Icons.Outlined.StopCircle, "停止生成", tint = AiNegative) }
        else IconButton(send, enabled = input.isNotBlank()) { Icon(Icons.Outlined.Send, "发送", tint = AiPurple) }
    }
    TextButton(openChat) { Text("在聊天 Tab 中继续查看") }
}

@Composable private fun MessageList(messages: List<ChatMessage>, copy: (ChatMessage) -> Unit, retry: (ChatMessage) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(messages, key = { it.id }) { message ->
            val assistant = message.role == "assistant"
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (assistant) Alignment.Start else Alignment.End) {
                Surface(color = if (assistant) Color.White else Color(0xFFEDE6FF), shape = RoundedCornerShape(14.dp)) {
                    Text(if (message.content.isBlank() && assistant) "正在生成…" else message.content, color = AiInk, lineHeight = 22.sp, modifier = Modifier.padding(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(aiTimeLabel(message.createdAt), color = AiMuted, fontSize = 10.sp)
                    if (assistant && message.content.isNotBlank()) TextButton({ copy(message) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("复制", fontSize = 11.sp) }
                    if (message.error) TextButton({ retry(message) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("重试", color = AiNegative, fontSize = 11.sp) }
                }
            }
        }
    }
}

@Composable private fun EmptyAiState(title: String, detail: String, action: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = AiMuted)
        Button(action, shape = RoundedCornerShape(12.dp)) { Text(if (title.contains("AI")) "配置 AI" else "连接 Binance") }
    }
}

@Composable private fun SessionNameDialog(title: String, initial: String, close: () -> Unit, confirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { OutlinedTextField(value, { value = it.take(40) }, singleLine = true, label = { Text("会话名称") }) }, confirmButton = { TextButton({ confirm(value) }, enabled = value.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(close) { Text("取消") } })
}
