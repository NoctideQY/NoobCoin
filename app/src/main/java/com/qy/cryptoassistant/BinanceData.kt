package com.qy.cryptoassistant

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class MarketTicker(
    val symbol: String, val name: String, val category: String,
    val priceUsdt: Double, val changePercent: Double,
    val high24h: Double? = null, val low24h: Double? = null,
    val volume24h: Double? = null, val quoteVolume24h: Double? = null,
    val tradeCount: Long? = null,
)
data class BinanceHolding(val asset: String, val free: Double, val locked: Double, val priceUsdt: Double?, val changePercent: Double) {
    val total: Double get() = free + locked
    val valueUsdt: Double? get() = priceUsdt?.let { total * it }
}
data class MarketSnapshot(val tickers: List<MarketTicker>, val usdtToCny: Double?, val receivedAt: Long = System.currentTimeMillis())
data class KlinePoint(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
)
data class CoinInfo(
    val symbol: String,
    val name: String,
    val description: String?,
    val homepage: String?,
    val fetchedAt: Long = System.currentTimeMillis(),
    val fromCache: Boolean = false,
)

object BinanceApi {
    private const val MARKET_BASE = "https://data-api.binance.vision"
    private const val ACCOUNT_BASE = "https://api.binance.com"
    private data class Meta(val pair: String, val symbol: String, val name: String, val category: String)
    private val supported = listOf(
        Meta("BTCUSDT", "BTC", "Bitcoin", "Layer 1"), Meta("ETHUSDT", "ETH", "Ethereum", "Layer 1"),
        Meta("SOLUSDT", "SOL", "Solana", "Layer 1"), Meta("BNBUSDT", "BNB", "BNB", "Layer 1"),
        Meta("XRPUSDT", "XRP", "XRP", "Layer 1"), Meta("DOGEUSDT", "DOGE", "Dogecoin", "Meme"),
        Meta("USDCUSDT", "USDC", "USD Coin", "稳定币"),
    )

    fun fetchMarket(): MarketSnapshot {
        val wanted = supported.associateBy { it.pair }
        val tickers = buildList {
            val array = JSONArray(get("/api/v3/ticker/24hr"))
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val pair = item.getString("symbol")
                if (!pair.endsWith("USDT") || item.getDouble("lastPrice") <= 0 ||
                    item.getDouble("quoteVolume") <= 0) continue
                val asset = pair.removeSuffix("USDT")
                val meta = wanted[pair] ?: Meta(pair, asset, asset, "其他")
                add(MarketTicker(meta.symbol, meta.name, meta.category,
                    item.getDouble("lastPrice"), item.getDouble("priceChangePercent"),
                    item.getDouble("highPrice"), item.getDouble("lowPrice"),
                    item.getDouble("volume"), item.getDouble("quoteVolume"), item.getLong("count")))
            }
        }
        val fx = runCatching { JSONObject(getExternal("https://open.er-api.com/v6/latest/USD")).getJSONObject("rates").getDouble("CNY") }.getOrNull()
        require(tickers.isNotEmpty()) { "行情接口未返回有效数据，请稍后刷新。" }
        return MarketSnapshot(tickers.sortedBy { supported.indexOfFirst { meta -> meta.symbol == it.symbol }.let { index -> if (index < 0) Int.MAX_VALUE else index } }, fx)
    }

    fun fetchAccount(apiKey: String, secret: String): List<BinanceHolding> {
        require(apiKey.isNotBlank() && secret.isNotBlank()) { "请填写 API Key 和 Secret Key。" }
        val serverTime = JSONObject(getExternal("$ACCOUNT_BASE/api/v3/time")).getLong("serverTime")
        val offset = serverTime - System.currentTimeMillis()
        fun signed(path: String): String {
            val query = "timestamp=${System.currentTimeMillis() + offset}&recvWindow=10000"
            return getAccount("$path?$query&signature=${hmacSha256(secret, query)}", apiKey)
        }
        val permission = JSONObject(signed("/sapi/v1/account/apiRestrictions"))
        val flags = permission.keys().asSequence().filter { it.startsWith("enable") }
            .associateWith { permission.getBoolean(it) }
        validateReadOnly(flags)
        val json = signed("/api/v3/account")
        // Keep every nonzero balance, even when public pricing is unavailable.
        val market = runCatching { fetchMarket().tickers.associateBy { it.symbol } }.getOrDefault(emptyMap())
        val balances = JSONObject(json).getJSONArray("balances")
        return buildList {
            for (i in 0 until balances.length()) {
                val item = balances.getJSONObject(i)
                val free = item.getString("free").toDouble()
                val locked = item.getString("locked").toDouble()
                val asset = item.getString("asset")
                if (free + locked <= 0.0) continue
                val ticker = if (asset == "USDT") MarketTicker("USDT", "Tether", "稳定币", 1.0, 0.0) else market[asset]
                add(BinanceHolding(asset, free, locked, ticker?.priceUsdt, ticker?.changePercent ?: 0.0))
            }
        }.sortedByDescending { it.valueUsdt ?: -1.0 }
    }

    fun fetchKlines(symbol: String, interval: String = "1d", limit: Int = 30): List<KlinePoint> {
        require(symbol.matches(Regex("[A-Za-z0-9]{1,30}"))) { "无效的币种代码。" }
        require(interval in listOf("15m", "1h", "4h", "1d", "1w") && limit in 2..1000)
        val path = "/api/v3/klines?symbol=${symbol.uppercase()}USDT&interval=$interval&limit=$limit"
        // The data-api host is the preferred public market-data endpoint. A second
        // public host helps on networks where its DNS route is temporarily slow.
        val body = runCatching { get(path) }.getOrElse {
            getExternal("https://api.binance.com$path")
        }
        val array = JSONArray(body)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONArray(i)
                add(KlinePoint(
                    openTime = item.getLong(0),
                    open = item.getString(1).toDouble(),
                    high = item.getString(2).toDouble(),
                    low = item.getString(3).toDouble(),
                    close = item.getString(4).toDouble(),
                    volume = item.getString(5).toDouble(),
                ))
            }
        }
    }

    private fun get(path: String) = getExternal(MARKET_BASE + path)
    private fun getAccount(path: String, apiKey: String) = getExternal(ACCOUNT_BASE + path, apiKey)
    private fun getExternal(urlString: String, apiKey: String? = null): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 15_000
            instanceFollowRedirects = false
            apiKey?.let { setRequestProperty("X-MBX-APIKEY", it) }
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val apiCode = runCatching { JSONObject(body).optInt("code") }.getOrNull()
                throw IllegalStateException(when {
                    code == 451 || code == 403 -> "接口拒绝访问（HTTP $code），请检查所在地区及账户的服务可用性。"
                    code == 429 || code == 418 -> "请求过于频繁，请稍后再试（HTTP $code）。"
                    apiCode == -2015 || apiCode == -2014 -> "Key、IP 白名单或读取权限不正确，请检查 Binance API 设置。"
                    apiCode == -1022 -> "签名校验失败，请检查 Secret Key。"
                    apiCode == -1021 -> "请求时间校验失败，请检查设备时间并重试。"
                    else -> "接口请求失败（HTTP $code），请稍后重试。"
                })
            }
            return body
        } finally {
            connection.disconnect()
        }
    }
    fun hmacSha256(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun validateReadOnly(flags: Map<String, Boolean>) {
        require(flags["enableReading"] == true) { "未开启读取权限，无法连接。" }
        require(flags.none { (name, enabled) -> name.startsWith("enable") && name != "enableReading" && enabled }) {
            "检测到非只读权限，请关闭交易、合约、提现和转账等权限后重试。"
        }
    }
}

object ProjectInfoApi {
    private const val CACHE_PREFS = "project_info_cache"
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private val memory = java.util.concurrent.ConcurrentHashMap<String, CoinInfo>()
    private var lastRequestAt = 0L
    private val requestLock = Any()
    private val ids = mapOf(
        "BTC" to "bitcoin", "ETH" to "ethereum", "SOL" to "solana", "BNB" to "binancecoin",
        "XRP" to "ripple", "DOGE" to "dogecoin", "USDT" to "tether", "USDC" to "usd-coin"
    )

    fun fetch(context: Context, symbol: String, forceRefresh: Boolean = false): CoinInfo {
        val normalized = symbol.uppercase()
        val cached = readCache(context, normalized) ?: memory[normalized]
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.fetchedAt <= CACHE_TTL_MS) {
            return cached.copy(fromCache = true)
        }
        val id = ids[symbol.uppercase()] ?: symbol.lowercase()
        val url = "https://api.coingecko.com/api/v3/coins/$id?localization=true&tickers=false&market_data=false&community_data=false&developer_data=false"
        return try {
            synchronized(requestLock) {
                val waitMs = 1_200L - (System.currentTimeMillis() - lastRequestAt)
                if (waitMs > 0) Thread.sleep(waitMs)
                lastRequestAt = System.currentTimeMillis()
            }
            val json = JSONObject(get(url))
            val descriptions = json.optJSONObject("description")
            val description = listOf("zh", "zh-tw", "en")
                .asSequence()
                .mapNotNull { descriptions?.optString(it)?.stripHtml()?.trim()?.takeIf(String::isNotBlank) }
                .firstOrNull()
            val homepage = json.optJSONObject("links")?.optJSONArray("homepage")?.optString(0)?.takeIf { it.isNotBlank() }
            val info = CoinInfo(normalized, json.optString("name", symbol), description, homepage)
            memory[normalized] = info
            writeCache(context, info)
            info
        } catch (error: Exception) {
            cached?.copy(fromCache = true) ?: throw error
        }
    }

    private fun get(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NoobCoin/0.1")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                if (code == 429) {
                    val retryAfter = connection.getHeaderField("Retry-After")
                    val suffix = retryAfter?.takeIf { it.isNotBlank() }?.let { "，建议 ${it} 秒后再试" }.orEmpty()
                    throw IllegalStateException("项目资料请求过于频繁（HTTP 429）$suffix。行情和 K 线不受影响。")
                }
                throw IllegalStateException("项目资料接口暂时不可用（HTTP $code）。")
            }
            return body
        } finally { connection.disconnect() }
    }

    private fun readCache(context: Context, symbol: String): CoinInfo? {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val fetchedAt = prefs.getLong("${symbol}_time", 0L).takeIf { it > 0 } ?: return null
        val name = prefs.getString("${symbol}_name", null) ?: return null
        val description = prefs.getString("${symbol}_description", null)
        val homepage = prefs.getString("${symbol}_homepage", null)
        return CoinInfo(symbol, name, description, homepage, fetchedAt, true)
    }

    private fun writeCache(context: Context, info: CoinInfo) {
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
            .putLong("${info.symbol}_time", info.fetchedAt)
            .putString("${info.symbol}_name", info.name)
            .putString("${info.symbol}_description", info.description)
            .putString("${info.symbol}_homepage", info.homepage)
            .apply()
    }

    private fun String.stripHtml(): String = replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ")
}

object SecretStore {
    private const val PREFS = "local_secrets"
    private const val ALIAS = "crypto_asset_assistant_key"
    fun read(context: Context, name: String): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null)?.let { decrypt(it) }
    fun write(context: Context, name: String, value: String) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(name, encrypt(value)).apply() }
    fun remove(context: Context, vararg names: String) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        names.forEach { editor.remove(it) }
        editor.apply()
    }
    fun writePair(context: Context, key: String, secret: String) {
        val encryptedKey = encrypt(key)
        val encryptedSecret = encrypt(secret)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("binance_api_key", encryptedKey).putString("binance_secret_key", encryptedSecret).commit()) {
            "密钥未能保存到本地，请重试。"
        }
    }
    @Synchronized private fun key(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? javax.crypto.SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String { val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP) }
    private fun decrypt(value: String): String = runCatching { val all = Base64.decode(value, Base64.NO_WRAP); val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12))); String(cipher.doFinal(all.copyOfRange(12, all.size)), StandardCharsets.UTF_8) }.getOrNull().orEmpty()
}
