package com.qy.cryptoassistant

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Purple = Color(0xFF7132F5)
private val Ink = Color(0xFF17151D)
private val Muted = Color(0xFF686B82)
private val Positive = Color(0xFF149E61)
private val Negative = Color(0xFFBC3642)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CryptoAssetApp() }
    }
}

private fun timeLabel(time: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(time))

private fun message(error: Throwable): String {
    if (error is CancellationException) throw error
    return when (error) {
        is java.net.SocketTimeoutException -> "请求超时，请检查网络后重试。"
        is java.net.UnknownHostException -> "无法解析服务地址，请检查设备网络。"
        is java.io.IOException -> "网络连接失败，请检查设备网络和接口可用性。"
        else -> error.message ?: "请求失败，请重试。"
    }
}

private fun amount(value: Double?, currency: String, fx: Double?): String {
    if (value == null) return "未估值"
    val converted = if (currency == "CNY" && fx != null) value * fx else value
    val prefix = when {
        currency == "CNY" && fx != null -> "≈¥"
        currency == "USD" -> "≈$"
        else -> "USDT "
    }
    val digits = if (converted != 0.0 && kotlin.math.abs(converted) < 0.01) 8 else 2
    return prefix + String.format(Locale.US, "%,.${digits}f", converted)
}

private fun compactNumber(value: Double): String = when {
    value >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", value / 1_000_000_000)
    value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000)
    value >= 1_000 -> String.format(Locale.US, "%.2fK", value / 1_000)
    else -> String.format(Locale.US, "%.2f", value)
}

@Composable
private fun CryptoAssetApp() {
    val context = LocalContext.current.applicationContext
    val prefs = remember { context.getSharedPreferences("preferences", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var currency by remember { mutableStateOf(prefs.getString("currency", "CNY") ?: "CNY") }
    var market by remember { mutableStateOf<MarketSnapshot?>(null) }
    var marketBusy by remember { mutableStateOf(false) }
    var marketError by remember { mutableStateOf<String?>(null) }
    var holdings by remember { mutableStateOf<List<BinanceHolding>?>(null) }
    var accountTime by remember { mutableLongStateOf(0L) }
    var accountBusy by remember { mutableStateOf(false) }
    var accountError by remember { mutableStateOf<String?>(null) }
    var configured by remember { mutableStateOf(!SecretStore.read(context, "binance_api_key").isNullOrBlank()) }
    var accountGeneration by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var aiProvider by remember { mutableStateOf(SecretStore.read(context, "ai_provider") ?: "DeepSeek") }
    var aiConfigured by remember { mutableStateOf(!SecretStore.read(context, "ai_key").isNullOrBlank()) }
    var aiResult by remember { mutableStateOf<String?>(null) }
    var aiTime by remember { mutableLongStateOf(0L) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var confirmAi by remember { mutableStateOf(false) }
    var selectedSymbol by remember { mutableStateOf<String?>(null) }
    var selectedTicker by remember { mutableStateOf<MarketTicker?>(null) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }

    fun toggleFavorite(symbol: String) {
        favorites = if (symbol in favorites) favorites - symbol else favorites + symbol
        prefs.edit().putStringSet("favorites", favorites).apply()
    }

    suspend fun refreshMarket() {
        if (marketBusy) return
        marketBusy = true
        try {
            market = withContext(Dispatchers.IO) { BinanceApi.fetchMarket() }
            marketError = null
        } catch (e: Exception) { marketError = message(e) }
        finally { marketBusy = false }
    }

    fun connect(key: String, secret: String) {
        if (accountBusy) return
        val generation = ++accountGeneration
        holdings = null
        aiResult = null
        accountBusy = true
        accountError = null
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { BinanceApi.fetchAccount(key.trim(), secret.trim()) }
                if (generation == accountGeneration) {
                    SecretStore.writePair(context, key.trim(), secret.trim())
                    configured = true
                    holdings = data
                    accountTime = System.currentTimeMillis()
                    dialog = null
                }
            } catch (e: Exception) {
                if (generation == accountGeneration) accountError = message(e)
            } finally { if (generation == accountGeneration) accountBusy = false }
        }
    }

    fun refreshAccount() {
        val key = SecretStore.read(context, "binance_api_key")
        val secret = SecretStore.read(context, "binance_secret_key")
        if (!key.isNullOrBlank() && !secret.isNullOrBlank()) connect(key, secret)
        else dialog = "binance"
    }

    fun disconnect() {
        accountGeneration++
        SecretStore.remove(context, "binance_api_key", "binance_secret_key")
        configured = false
        holdings = null
        accountError = null
        accountBusy = false
        aiResult = null
        dialog = null
    }

    fun analyze() {
        val snapshot = holdings ?: return
        val generation = accountGeneration
        val key = SecretStore.read(context, "ai_key") ?: return
        val model = SecretStore.read(context, "ai_model") ?: return
        val provider = aiProvider
        aiBusy = true
        aiError = null
        aiResult = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { AiApi.analyze(provider, key, model, snapshot) }
                if (generation == accountGeneration) { aiResult = result; aiTime = accountTime }
            } catch (e: Exception) { aiError = message(e) }
            finally { aiBusy = false }
        }
    }

    LaunchedEffect(Unit) {
        if (configured) refreshAccount()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, tab) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (tab == 1 || tab == 2 || market == null) refreshMarket()
            while (tab == 1 || tab == 2) { delay(60_000); refreshMarket() }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Purple, onPrimary = Color.White,
        surface = Color.White, background = Color(0xFFF8F7FC), onSurface = Ink)) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
            NavigationBar(containerColor = Color.White) {
                listOf("资产" to Icons.Outlined.Home, "行情" to Icons.Outlined.BarChart,
                    "自选" to Icons.Outlined.Star, "AI" to Icons.Outlined.AutoAwesome,
                    "我的" to Icons.Outlined.Person).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; selectedSymbol = null; selectedTicker = null },
                        icon = { Icon(icon, label) }, label = { Text(label) })
                }
            }
        }) { padding ->
            val modifier = Modifier.padding(padding)
            if (selectedSymbol != null) {
                CoinDetailScreen(
                    modifier = modifier,
                    ticker = market?.tickers?.firstOrNull { it.symbol == selectedSymbol } ?: selectedTicker,
                    currency = currency,
                    fx = market?.usdtToCny,
                    marketTime = market?.receivedAt,
                    marketError = marketError,
                    symbol = selectedSymbol!!,
                    favorite = selectedSymbol!! in favorites,
                    onBack = { selectedSymbol = null; selectedTicker = null },
                    onToggleFavorite = { toggleFavorite(selectedSymbol!!) },
                )
            } else when (tab) {
                0 -> PortfolioScreen(modifier, holdings, accountTime, accountBusy, accountError,
                    configured, currency, market?.usdtToCny, ::refreshAccount, { dialog = "binance" })
                1 -> MarketScreen(
                    modifier = modifier,
                    snapshot = market,
                    busy = marketBusy,
                    error = marketError,
                    currency = currency,
                    holdings = holdings,
                    favorites = favorites,
                    onToggleFavorite = ::toggleFavorite,
                    onOpenDetail = { ticker -> selectedTicker = ticker; selectedSymbol = ticker.symbol },
                    refresh = { scope.launch { refreshMarket() } },
                )
                2 -> WatchlistScreen(
                    modifier = modifier,
                    snapshot = market,
                    holdings = holdings,
                    favorites = favorites,
                    currency = currency,
                    onToggleFavorite = ::toggleFavorite,
                    onOpenDetail = { ticker -> selectedTicker = ticker; selectedSymbol = ticker.symbol },
                    onConnect = { dialog = "binance" },
                    accountTime = accountTime,
                    busy = marketBusy || accountBusy,
                    error = marketError ?: accountError,
                    refresh = { scope.launch { refreshMarket() }; if (configured) refreshAccount() },
                )
                3 -> Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Heading("AI 组合分析")
                    SettingRow("AI Provider", if (aiConfigured) "$aiProvider · 已配置" else "未配置",
                        { dialog = "ai" })
                    when {
                        holdings == null -> EmptyContent("尚无真实持仓", "请先连接 Binance 只读账户。",
                            "连接 Binance", { dialog = "binance" })
                        !aiConfigured -> EmptyContent("尚未配置 AI", "DeepSeek / Kimi", "配置 API", { dialog = "ai" })
                        holdings!!.isEmpty() -> Text("现货账户暂无非零余额。", color = Muted)
                        holdings!!.any { it.valueUsdt == null } -> Text("部分持仓尚未取得价格，请刷新账户后再分析。", color = Muted)
                        else -> {
                            Text("账户快照：${timeLabel(accountTime)}", color = Muted)
                            Button(onClick = { confirmAi = true }, enabled = !aiBusy && !accountBusy,
                                shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Outlined.AutoAwesome, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (aiBusy) "正在分析…" else "分析持仓")
                            }
                        }
                    }
                    aiError?.let { ErrorState(it) }
                    aiResult?.let {
                        Text("持仓快照：${timeLabel(aiTime)} · $aiProvider", color = Muted, fontSize = 12.sp)
                        Text(it, lineHeight = 24.sp)
                        Text("AI 可能出错；以上不是交易指令。", color = Muted, fontSize = 12.sp)
                    }
                }
                else -> Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Heading("我的")
                    SettingRow("Binance 现货", when {
                        accountBusy -> "正在读取账户…"
                        holdings != null -> "已连接 · 只读权限已验证"
                        configured -> "已保存 · 尚未读取成功"
                        else -> "未连接"
                    }, { dialog = "binance" })
                    SettingRow("AI Provider", if (aiConfigured) "$aiProvider · 已配置" else "DeepSeek / Kimi · 未配置",
                        { dialog = "ai" })
                    Text("计价货币", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("CNY", "USD", "USDT").forEach { value ->
                            FilterChip(currency == value, {
                                currency = value
                                prefs.edit().putString("currency", value).apply()
                            }, label = { Text(value) })
                        }
                    }
                    Text("密钥在设备上使用 Android Keystore 加密保存。Binance 密钥不会发送给 AI。", color = Muted)
                    Text("不支持下单、提现或转账。只读取现货账户，不包含理财、资金或合约账户。", color = Muted)
                    accountError?.let { ErrorState(it) }
                }
            }
        }

        if (dialog == "binance" && selectedSymbol == null) BinanceDialog(accountBusy, accountError, configured,
            { dialog = null }, ::connect, ::refreshAccount, ::disconnect)
        if (dialog == "ai" && selectedSymbol == null) AiDialog(aiProvider, {
            aiProvider = SecretStore.read(context, "ai_provider") ?: "DeepSeek"
            aiConfigured = !SecretStore.read(context, "ai_key").isNullOrBlank()
            aiResult = null
            dialog = null
        }, { dialog = null })
        if (confirmAi) AlertDialog(onDismissRequest = { confirmAi = false },
            title = { Text("发送持仓摘要给 $aiProvider？") },
            text = { Text("仅发送币种和持仓占比，不发送余额数量、账户总额或 Binance 密钥。请求可能产生服务商 API 费用。") },
            confirmButton = { TextButton(onClick = { confirmAi = false; analyze() }) { Text("确认并分析") } },
            dismissButton = { TextButton(onClick = { confirmAi = false }) { Text("取消") } })
    }
}

@Composable
private fun WatchlistScreen(
    modifier: Modifier,
    snapshot: MarketSnapshot?,
    holdings: List<BinanceHolding>?,
    favorites: Set<String>,
    currency: String,
    onToggleFavorite: (String) -> Unit,
    onOpenDetail: (MarketTicker) -> Unit,
    onConnect: () -> Unit,
    accountTime: Long,
    busy: Boolean,
    error: String?,
    refresh: () -> Unit,
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val tickerMap = snapshot?.tickers.orEmpty().associateBy { it.symbol }
    val favoriteTickers = favorites.mapNotNull { tickerMap[it] }
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自选", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                IconButton(refresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, "刷新数据", tint = Purple) }
            }
            TabRow(selectedTabIndex = section, containerColor = Color.Transparent) {
                Tab(selected = section == 0, onClick = { section = 0 }, text = { Text("我的持仓") })
                Tab(selected = section == 1, onClick = { section = 1 }, text = { Text("星标关注 (${favorites.size})") })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text("刷新失败：$it", color = Negative, fontSize = 12.sp) }
            snapshot?.let { Text("行情：${timeLabel(it.receivedAt)}", color = Muted, fontSize = 11.sp) }
        }
        if (section == 0) {
        when {
            holdings == null -> item {
                EmptyContent("连接 Binance 查看持仓", "未连接账户时不会显示余额或模拟资产。", "连接 Binance", onConnect)
            }
            holdings.isEmpty() -> item { Text("现货账户暂无非零余额。", color = Muted) }
            else -> {
                item { Text("账户快照：${timeLabel(accountTime)}", color = Muted, fontSize = 12.sp) }
                items(holdings, key = { "holding-${it.asset}" }) { holding ->
                    val ticker = tickerMap[holding.asset]
                    Row(Modifier.fillMaxWidth().clickable(enabled = ticker != null) { ticker?.let(onOpenDetail) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoinMark(holding.asset)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(holding.asset, fontWeight = FontWeight.SemiBold)
                            Text(java.math.BigDecimal.valueOf(holding.total).stripTrailingZeros().toPlainString(), color = Muted, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(amount(holding.valueUsdt, currency, snapshot?.usdtToCny), fontSize = 14.sp)
                            ticker?.let { Text(String.format(Locale.US, "%+.2f%%", it.changePercent), color = if (it.changePercent >= 0) Positive else Negative, fontSize = 12.sp) }
                        }
                    }
                    HorizontalDivider(color = Color(0xFFE8E6ED))
                }
            }
        }
        } else {
        item {
            Text("星标关注", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            if (favoriteTickers.isEmpty()) Text("还没有星标币种。去行情页点击星标添加。", color = Muted, modifier = Modifier.padding(top = 4.dp))
        }
        items(favorites.filter { it !in tickerMap }.sorted(), key = { "missing-$it" }) { symbol ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$symbol · 暂无行情", Modifier.weight(1f), color = Muted)
                IconButton({ onToggleFavorite(symbol) }) { Icon(Icons.Outlined.Star, "取消自选", tint = Purple) }
            }
        }
        items(favoriteTickers, key = { "favorite-${it.symbol}" }) { ticker ->
            Row(Modifier.fillMaxWidth().clickable { onOpenDetail(ticker) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CoinMark(ticker.symbol)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(ticker.symbol, fontWeight = FontWeight.SemiBold)
                    Text(ticker.name, color = Muted, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(amount(ticker.priceUsdt, currency, snapshot?.usdtToCny), fontSize = 14.sp)
                    Text(String.format(Locale.US, "%+.2f%%", ticker.changePercent), color = if (ticker.changePercent >= 0) Positive else Negative, fontSize = 12.sp)
                }
                IconButton(onClick = { onToggleFavorite(ticker.symbol) }) { Icon(Icons.Outlined.Star, "取消自选", tint = Purple) }
            }
            HorizontalDivider(color = Color(0xFFE8E6ED))
        }
        }
    }
}

@Composable private fun PortfolioScreen(modifier: Modifier, holdings: List<BinanceHolding>?, time: Long,
    busy: Boolean, error: String?, configured: Boolean, currency: String, fx: Double?,
    refresh: () -> Unit, settings: () -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("我的资产", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(refresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, "刷新账户", tint = Purple) }
        } }
        if (busy) item { LoadingState("正在验证权限并读取账户…") }
        error?.let { item { ErrorState(it) } }
        if (holdings == null && !busy) item {
            EmptyContent(if (configured) "账户读取未完成" else "连接 Binance 查看资产",
                "没有账户数据时，不显示余额或资产走势。",
                if (configured) "重新读取" else "连接 Binance", if (configured) refresh else settings)
        }
        if (holdings != null) {
            val missing = holdings.count { it.valueUsdt == null }
            val known = holdings.mapNotNull { it.valueUsdt }.sum()
            item {
                Text(if (missing > 0) "已估值部分 · 不含 $missing 项资产" else "现货账户估值", color = Muted)
                Text(if (missing == holdings.size && missing > 0) "暂无法估值" else amount(known, currency, fx),
                    fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("账户快照：${timeLabel(time)}", color = Muted, fontSize = 12.sp)
                CurrencyNote(currency, fx)
                if (known > 0) {
                    Text("持仓分布 · 已估值部分", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                    holdings.filter { (it.valueUsdt ?: 0.0) > 0 }.take(5).forEach { holding ->
                        val share = (holding.valueUsdt!! / known).toFloat().coerceIn(0f, 1f)
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(holding.asset, fontSize = 12.sp)
                            Text(String.format(Locale.US, "%.1f%%", share * 100), fontSize = 12.sp)
                        }
                        LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
            if (holdings.isEmpty()) item { Text("现货账户没有非零余额。", color = Muted) }
            items(holdings, key = { it.asset }) { holding ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CoinMark(holding.asset)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(holding.asset, fontWeight = FontWeight.SemiBold)
                        Text(java.math.BigDecimal.valueOf(holding.total).stripTrailingZeros().toPlainString(), color = Muted, fontSize = 12.sp)
                        Text("冻结 ${holding.locked}", color = Muted, fontSize = 11.sp)
                    }
                    Text(amount(holding.valueUsdt, currency, fx), fontSize = 14.sp)
                }
                HorizontalDivider(Modifier.padding(top = 12.dp), color = Color(0xFFE8E6ED))
            }
            item { Text("暂无历史快照。", color = Muted, fontSize = 12.sp) }
        }
    }
}

@Composable private fun MarketScreen(
    modifier: Modifier,
    snapshot: MarketSnapshot?,
    busy: Boolean,
    error: String?,
    currency: String,
    holdings: List<BinanceHolding>?,
    favorites: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onOpenDetail: (MarketTicker) -> Unit,
    refresh: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("行情") }
    var orderName by rememberSaveable { mutableStateOf(MarketOrder.DEFAULT.name) }
    val order = MarketOrder.valueOf(orderName)
    val all = snapshot?.tickers.orEmpty()
    val heldSymbols = holdings.orEmpty().map { it.asset }.toSet()
    val visible = if (category == "自选") all.filter { it.symbol in favorites || it.symbol in heldSymbols } else all
    val filtered = filterMarket(visible, query,
        if (category == "行情" || category == "自选") "全部" else category, order)
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("加密货币", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                IconButton(refresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, "刷新行情", tint = Purple) }
            }
            Text("Binance · USDT 交易对 · 24h 涨跌幅", color = Muted, fontSize = 12.sp)
            snapshot?.let { Text("最近获取：${timeLabel(it.receivedAt)}", color = Muted, fontSize = 12.sp) }
            CurrencyNote(currency, snapshot?.usdtToCny)
            if (all.isNotEmpty()) {
                Text("${all.size} 个交易对 · 上涨 ${all.count { it.changePercent > 0 }} · 下跌 ${all.count { it.changePercent < 0 }}",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 12.dp),
                singleLine = true, placeholder = { Text("搜索币种") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("行情", "自选", "Layer 1", "Meme", "稳定币", "其他").forEach { item ->
                    FilterChip(category == item, { category = item }, label = { Text(item) })
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarketOrder.entries.forEach { value ->
                    FilterChip(order == value, { orderName = value.name }, label = { Text(value.label) })
                }
            }
            Text("${order.label} · ${filtered.size} 项", color = Muted, fontSize = 12.sp)
        }
        if (busy) item { LoadingState("正在获取行情…") }
        error?.let { item {
            ErrorState(if (snapshot == null) it else "$it 以下是上次获取的数据。")
            TextButton(refresh) { Text("重试") }
        } }
        if (!busy && error == null && filtered.isEmpty()) item {
            Text(if (category == "自选") "暂无自选或持仓币种。点击星标添加自选。" else "没有匹配的币种。", color = Muted)
        }
        items(filtered, key = { it.symbol }) { ticker ->
            Row(Modifier.fillMaxWidth().clickable { onOpenDetail(ticker) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CoinMark(ticker.symbol)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(ticker.symbol, fontWeight = FontWeight.SemiBold)
                    Text(ticker.name, color = Muted, fontSize = 12.sp)
                    ticker.quoteVolume24h?.let { Text("24h ${compactNumber(it)} USDT", color = Muted, fontSize = 10.sp) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(amount(ticker.priceUsdt, currency, snapshot?.usdtToCny), fontSize = 14.sp)
                    Text(String.format(Locale.US, "%+.2f%%", ticker.changePercent),
                        color = if (ticker.changePercent >= 0) Positive else Negative, fontSize = 12.sp)
                }
                IconButton(onClick = { onToggleFavorite(ticker.symbol) }) {
                    Icon(if (ticker.symbol in favorites) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (ticker.symbol in favorites) "取消自选" else "加入自选",
                        tint = if (ticker.symbol in favorites) Purple else Muted)
                }
            }
            HorizontalDivider(color = Color(0xFFE8E6ED))
        }
    }
}

@Composable
private fun CoinDetailScreen(
    modifier: Modifier,
    ticker: MarketTicker?,
    currency: String,
    fx: Double?,
    marketTime: Long?,
    marketError: String?,
    symbol: String,
    favorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var interval by rememberSaveable(symbol) { mutableStateOf("1d") }
    var klines by remember(symbol, interval) { mutableStateOf<List<KlinePoint>>(emptyList()) }
    var info by remember(symbol) { mutableStateOf<CoinInfo?>(null) }
    var klinesLoading by remember(symbol, interval) { mutableStateOf(true) }
    var infoLoading by remember(symbol) { mutableStateOf(true) }
    var klinesError by remember(symbol, interval) { mutableStateOf<String?>(null) }
    var infoError by remember(symbol) { mutableStateOf<String?>(null) }
    var chartRetry by remember { mutableIntStateOf(0) }
    var infoRetry by remember { mutableIntStateOf(0) }
    var expanded by rememberSaveable(symbol) { mutableStateOf(false) }
    var candleIndex by remember(symbol, interval) { mutableIntStateOf(59) }
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    LaunchedEffect(symbol, interval, chartRetry) {
        klinesLoading = true
        klinesError = null
        runCatching { withContext(Dispatchers.IO) { BinanceApi.fetchKlines(symbol, interval, 60) } }
            .onSuccess { klines = it; candleIndex = it.lastIndex.coerceAtLeast(0) }
            .onFailure { klinesError = message(it) }
        klinesLoading = false
    }
    LaunchedEffect(symbol, infoRetry) {
        infoLoading = true
        infoError = null
        runCatching { withContext(Dispatchers.IO) { ProjectInfoApi.fetch(context, symbol, forceRefresh = infoRetry > 0) } }
            .onSuccess { info = it }
            .onFailure { infoError = message(it) }
        infoLoading = false
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text(ticker?.name ?: symbol, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(symbol, color = Muted, fontSize = 13.sp)
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, "自选", tint = Purple)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (ticker != null) {
            Text(amount(ticker.priceUsdt, currency, fx), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(String.format(Locale.US, "%+.2f%%  · Binance 24h", ticker.changePercent), color = if (ticker.changePercent >= 0) Positive else Negative)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailStat("24h 高", amount(ticker.high24h, currency, fx), Modifier.weight(1f))
                DetailStat("24h 低", amount(ticker.low24h, currency, fx), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailStat("24h 成交额", ticker.quoteVolume24h?.let(::compactNumber)?.plus(" USDT") ?: "暂无", Modifier.weight(1f))
                DetailStat("成交笔数", ticker.tradeCount?.let { compactNumber(it.toDouble()) } ?: "暂无", Modifier.weight(1f))
            }
        } else Text("正在读取实时价格…", color = Muted)
        marketTime?.let { Text("行情更新时间：${timeLabel(it)}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
        marketError?.let { Text("行情刷新提示：$it", color = Negative, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(20.dp))
        Text("项目介绍", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Surface(color = Color.White, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                when {
                    infoLoading && info == null -> LoadingState("正在获取项目资料…")
                    info?.description != null -> {
                        info?.let { if (it.fromCache) Text("本机缓存资料 · ${timeLabel(it.fetchedAt)}", color = Muted, fontSize = 11.sp) }
                        Text(info!!.description!!, color = Ink, lineHeight = 22.sp,
                            maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "展开介绍") }
                    }
                    else -> Text(infoError ?: "项目介绍暂时不可用，未使用模拟内容。", color = Muted)
                }
                info?.homepage?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { url ->
                    TextButton({ runCatching { uriHandler.openUri(url) }.onFailure { linkError = "无法打开浏览器。" } }) {
                        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("项目官网")
                    }
                }
                linkError?.let { Text(it, color = Negative, fontSize = 12.sp) }
                if (infoError != null) TextButton({ infoRetry++ }, enabled = !infoLoading) { Text("重试项目资料") }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("K 线", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${interval.uppercase()} · Binance", color = Muted, fontSize = 12.sp)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("15m", "1h", "4h", "1d", "1w").forEach { value ->
                FilterChip(interval == value, { interval = value }, label = { Text(value) })
            }
        }
        Surface(color = Color.White, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            if (klines.size >= 2) {
                Column(Modifier.padding(12.dp)) {
                    Text("最高 ${amount(klines.maxOf { it.high }, "USDT", null)}", color = Muted, fontSize = 11.sp)
                    CandlestickChart(klines, Modifier.fillMaxWidth().height(180.dp).padding(vertical = 8.dp))
                    Text("最低 ${amount(klines.minOf { it.low }, "USDT", null)}", color = Muted, fontSize = 11.sp)
                    Text("成交量 ($symbol)", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                    VolumeChart(klines, Modifier.fillMaxWidth().height(48.dp).padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(timeLabel(klines.first().openTime), color = Muted, fontSize = 10.sp)
                        Text(timeLabel(klines.last().openTime), color = Muted, fontSize = 10.sp)
                    }
                    val candle = klines[candleIndex.coerceIn(0, klines.lastIndex)]
                    Slider(value = candleIndex.toFloat(), onValueChange = { candleIndex = it.toInt() },
                        valueRange = 0f..klines.lastIndex.toFloat(), steps = (klines.size - 2).coerceAtLeast(0))
                    Text("${timeLabel(candle.openTime)} · ${if (candleIndex == klines.lastIndex) "最新周期，可能未收盘" else "历史周期"}", fontSize = 11.sp, color = Muted)
                    Text("开 ${amount(candle.open, "USDT", null)}\n高 ${amount(candle.high, "USDT", null)}\n低 ${amount(candle.low, "USDT", null)}\n收 ${amount(candle.close, "USDT", null)}\n量 ${compactNumber(candle.volume)} $symbol", fontSize = 12.sp)
                }
            } else if (klinesLoading) {
                Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) { LoadingState("正在获取 K 线…") }
            } else {
                Text(klinesError ?: "K 线暂无数据，未使用模拟数据。", color = Muted, modifier = Modifier.padding(16.dp))
            }
        }
        TextButton({ chartRetry++ }, enabled = !klinesLoading) {
            Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
            Text(if (klinesError != null) "重试 K 线" else "刷新 K 线")
        }
        listOfNotNull(infoError?.let { "项目资料：$it" }, klinesError?.let { "K 线：$it" }).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString("\n"), color = Negative, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
        Text("价格和 K 线来自 Binance 公共市场接口；项目资料来自 CoinGecko 公共接口。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp))
        CoinNotebook(symbol, ticker?.priceUsdt, currency, fx)
    }
}

@Composable
private fun VolumeChart(points: List<KlinePoint>, modifier: Modifier) {
    Canvas(modifier) {
        val max = points.maxOf { it.volume }.coerceAtLeast(0.00000001)
        val step = size.width / points.size
        points.forEachIndexed { i, point ->
            val height = (point.volume / max * size.height).toFloat()
            drawRect(if (point.close >= point.open) Positive else Negative,
                Offset(step * i, size.height - height), androidx.compose.ui.geometry.Size(step * 0.65f, height))
        }
    }
}

@Composable
private fun CoinNotebook(symbol: String, price: Double?, currency: String, fx: Double?) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("research_notes", Context.MODE_PRIVATE) }
    var quantity by rememberSaveable(symbol) { mutableStateOf("1") }
    var note by rememberSaveable(symbol) { mutableStateOf(prefs.getString(symbol, "").orEmpty()) }
    var saved by remember(symbol) { mutableStateOf(note) }
    Spacer(Modifier.height(24.dp))
    Text("币值换算", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    OutlinedTextField(quantity, { quantity = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text("数量 ($symbol)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    val result = convertedQuantity(quantity, price)
    Text(if (result == null) "请输入有效数量，且需要已获取行情。" else amount(result, currency, fx),
        modifier = Modifier.padding(vertical = 8.dp), color = if (result == null) Muted else Purple)
    Text("参考当前行情，不含手续费，不执行交易。", fontSize = 11.sp, color = Muted)
    Spacer(Modifier.height(24.dp))
    Text("研究笔记", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    OutlinedTextField(note, { note = it.take(4000) }, Modifier.fillMaxWidth().padding(top = 8.dp),
        minLines = 3, maxLines = 8, label = { Text("我的记录") }, supportingText = { Text("${note.length}/4000 · 仅保存在本机") })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button({ prefs.edit().putString(symbol, note).apply(); saved = note }, enabled = note != saved) {
            Icon(Icons.Outlined.Save, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("保存笔记")
        }
        Text(if (note == saved) "已保存" else "有未保存修改", Modifier.padding(start = 12.dp), color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun DetailStat(label: String, value: String, modifier: Modifier) {
    Surface(color = Color.White, shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, color = Muted, fontSize = 11.sp)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun CandlestickChart(points: List<KlinePoint>, modifier: Modifier) {
    Canvas(modifier) {
        val min = points.minOf { it.low }
        val max = points.maxOf { it.high }
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        fun y(value: Double): Float = size.height * (1f - ((value - min) / span).toFloat())
        val step = size.width / points.size.coerceAtLeast(1)
        val bodyWidth = (step * 0.55f).coerceIn(3f, 16f)
        points.forEachIndexed { index, point ->
            val x = step * index + step / 2f
            val color = if (point.close >= point.open) Positive else Negative
            drawLine(color, Offset(x, y(point.high)), Offset(x, y(point.low)), strokeWidth = 2f)
            val top = y(maxOf(point.open, point.close))
            val bottom = y(minOf(point.open, point.close))
            drawRect(color, topLeft = Offset(x - bodyWidth / 2f, top), size = androidx.compose.ui.geometry.Size(bodyWidth, (bottom - top).coerceAtLeast(2f)))
        }
        drawLine(Color(0xFFE8E6ED), Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 2f)
    }
}

@Composable private fun BinanceDialog(busy: Boolean, error: String?, configured: Boolean,
    close: () -> Unit, connect: (String, String) -> Unit, refresh: () -> Unit, disconnect: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("Binance 只读连接") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("仅支持 HMAC API Key。关闭交易、提现、转账和合约权限，保留读取权限。")
            SecretField("API Key", key, { key = it }, !busy)
            SecretField("Secret Key", secret, { secret = it }, !busy)
            if (busy) LoadingState("正在读取账户…")
            error?.let { ErrorState(it) }
            if (configured) {
                TextButton(refresh, enabled = !busy) { Text("使用已保存的 Key 刷新") }
                TextButton(disconnect, enabled = !busy) { Text("断开并删除本地密钥", color = Negative) }
            }
        }
    }, confirmButton = {
        TextButton({ connect(key, secret) }, enabled = !busy && key.isNotBlank() && secret.isNotBlank()) {
            Text("验证并保存")
        }
    }, dismissButton = { TextButton(close) { Text("关闭") } })
}

@Composable private fun AiDialog(initialProvider: String, saved: () -> Unit, close: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(initialProvider) }
    var key by remember { mutableStateOf("") }
    var verifiedKey by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var model by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    fun verify() {
        val candidate = key.trim().ifBlank {
            if (provider == initialProvider) SecretStore.read(context, "ai_key").orEmpty() else ""
        }
        if (candidate.isBlank()) { error = "请填写此服务商的 API Key。"; return }
        busy = true
        error = null
        scope.launch {
            try {
                models = withContext(Dispatchers.IO) { AiApi.models(provider, candidate) }
                model = models.firstOrNull { it == "deepseek-chat" || it == "moonshot-v1-8k" } ?: models.first()
                verifiedKey = candidate
            } catch (e: Exception) { error = message(e); models = emptyList(); verifiedKey = null }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text("AI API 配置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("DeepSeek", "Kimi").forEach { name ->
                    FilterChip(provider == name, {
                        provider = name; key = ""; verifiedKey = null; models = emptyList(); error = null
                    }, enabled = !busy, label = { Text(name) })
                }
            }
            SecretField("API Key（留空使用已保存密钥）", key, {
                key = it; verifiedKey = null; models = emptyList()
            }, !busy)
            TextButton(::verify, enabled = !busy) { Text(if (busy) "验证中…" else "验证 Key 并获取模型") }
            if (models.isNotEmpty()) {
                Box {
                    OutlinedButton({ expanded = true }) { Text(model); Icon(Icons.Outlined.ArrowDropDown, null) }
                    DropdownMenu(expanded, { expanded = false }) {
                        models.forEach { item -> DropdownMenuItem(text = { Text(item) },
                            onClick = { model = item; expanded = false }) }
                    }
                }
                Text("Key 已验证。选择支持对话的模型。", color = Positive, fontSize = 12.sp)
            }
            error?.let { ErrorState(it) }
            Text("验证不发送持仓；分析前会单独确认。", color = Muted, fontSize = 12.sp)
            TextButton({
                SecretStore.remove(context, "ai_key", "ai_model", "ai_provider")
                saved()
            }, enabled = !busy) { Text("删除 AI 配置", color = Negative) }
        }
    }, confirmButton = {
        TextButton({
            try {
                SecretStore.write(context, "ai_key", verifiedKey!!)
                SecretStore.write(context, "ai_model", model)
                SecretStore.write(context, "ai_provider", provider)
                saved()
            } catch (e: Exception) { error = message(e) }
        }, enabled = !busy && verifiedKey != null && model.isNotBlank()) { Text("保存") }
    }, dismissButton = { TextButton(close) { Text("关闭") } })
}

@Composable private fun SecretField(label: String, value: String, change: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        enabled = enabled, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
}

@Composable private fun Heading(title: String) { Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
@Composable private fun SettingRow(title: String, detail: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Purple)
    }
    HorizontalDivider(color = Color(0xFFE8E6ED))
}
@Composable private fun EmptyContent(title: String, detail: String, action: String, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Outlined.AccountBalanceWallet, null, Modifier.size(44.dp), tint = Purple)
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = Muted, lineHeight = 22.sp)
        Button(click, shape = RoundedCornerShape(12.dp)) { Text(action) }
    }
}
@Composable private fun CoinMark(symbol: String) {
    val color = when (symbol) { "BTC" -> Color(0xFFF7931A); "ETH" -> Color(0xFF627EEA); "USDC", "USDT" -> Positive; else -> Purple }
    Box(Modifier.size(36.dp).background(color, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
        Text(symbol.take(1), color = Color.White, fontWeight = FontWeight.Bold)
    }
}
@Composable private fun CurrencyNote(currency: String, fx: Double?) {
    Text(when {
        currency == "CNY" && fx != null -> "CNY 参考折算 · ExchangeRate-API 日汇率 · 按 1 USDT≈1 USD"
        currency == "CNY" -> "汇率暂不可用，显示 USDT 原始报价"
        currency == "USD" -> "USD 参考折算 · 按 1 USDT≈1 USD，非实际兑换价"
        else -> "USDT 原始报价"
    }, color = Muted, fontSize = 11.sp)
}
@Composable private fun LoadingState(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, Modifier.padding(start = 10.dp), color = Muted, fontSize = 13.sp)
    }
}
@Composable private fun ErrorState(text: String) { Text(text, color = Negative, fontSize = 13.sp, lineHeight = 20.sp) }
