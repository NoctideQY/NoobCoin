package com.qy.cryptoassistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AiApi {
    private fun base(provider: String): String = when (provider) {
        "Kimi" -> "https://api.moonshot.cn/v1"
        "DeepSeek" -> "https://api.deepseek.com"
        else -> error("不支持的 AI 服务商")
    }

    fun models(provider: String, apiKey: String): List<String> {
        val data = JSONObject(request(base(provider) + "/models", apiKey)).getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it).getString("id") }.sorted()
            .also { require(it.isNotEmpty()) { "该 Key 没有返回可用模型。" } }
    }

    fun analyze(provider: String, apiKey: String, model: String, holdings: List<BinanceHolding>): String {
        require(holdings.isNotEmpty() && holdings.all { it.valueUsdt != null }) { "持仓不完整，暂不能分析。" }
        val endpoint = base(provider) + "/chat/completions"
        val total = holdings.sumOf { it.valueUsdt!! }
        require(total > 0) { "暂无可分析的资产。" }
        val portfolio = holdings.joinToString("\n") {
            "${it.asset}: 占已估值持仓 ${String.format(java.util.Locale.US, "%.2f", it.valueUsdt!! / total * 100)}%"
        }
        val payload = JSONObject().apply {
            put("model", model); put("stream", false); put("max_tokens", 1600)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", "你是加密资产风险解释助手。只根据用户提供的持仓做事实性、可解释的风险分析，不预测价格，不给出买卖、下单、提现或转账指令。用中文，最多输出三点。"))
                put(JSONObject().put("role", "user").put("content", "请分析以下真实账户持仓的集中度、稳定币比例和需要关注的风险：\n$portfolio"))
            })
        }
        val body = request(endpoint, apiKey, payload)
        return JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            .getString("content").also { require(it.isNotBlank() && it != "null") { "模型未返回分析正文，请换用对话模型。" } }
    }

    private fun request(endpoint: String, apiKey: String, payload: JSONObject? = null): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = if (payload == null) "GET" else "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            if (payload != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException(when (code) {
                401 -> "AI Key 无效，请检查所选服务商与 Key 是否匹配。"
                402 -> "AI 账户余额不足，请检查服务商账户。"
                403, 451 -> "AI 接口拒绝访问，请检查账户权限和服务可用地区。"
                429 -> "AI 请求受限或配额不足，请稍后再试。"
                else -> "AI 请求失败（HTTP $code），请检查模型是否支持对话。"
            })
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
