package com.qy.cryptoassistant

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URL

data class NewsItem(val title: String, val source: String, val publishedAt: String, val url: String)
data class GithubRelease(val tagName: String, val name: String, val body: String, val htmlUrl: String, val publishedAt: String)

object NewsApi {
    private const val RSS_URL = "https://www.coindesk.com/arc/outboundfeeds/rss/"

    fun fetchLatest(limit: Int = 12): List<NewsItem> {
        val connection = (URL(RSS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "NoobCoin/0.1")
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("新闻接口暂时不可用（HTTP ${connection.responseCode}）。")
            val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                setInput(connection.inputStream.bufferedReader())
            }
            val result = mutableListOf<NewsItem>()
            var event = parser.eventType
            var insideItem = false
            var title: String? = null
            var link: String? = null
            var date: String? = null
            while (event != XmlPullParser.END_DOCUMENT && result.size < limit) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                        "item" -> { insideItem = true; title = null; link = null; date = null }
                        "title" -> if (insideItem) title = parser.nextText().trim()
                        "link" -> if (insideItem) link = parser.nextText().trim()
                        "pubdate", "published", "updated" -> if (insideItem && date == null) date = parser.nextText().trim()
                    }
                    XmlPullParser.END_TAG -> if (parser.name.equals("item", true) && insideItem) {
                        if (!title.isNullOrBlank() && !link.isNullOrBlank()) result += NewsItem(title!!, "CoinDesk", date.orEmpty(), link!!)
                        insideItem = false
                    }
                }
                event = parser.next()
            }
            return result
        } finally { connection.disconnect() }
    }
}

object GithubApi {
    private const val LATEST = "https://api.github.com/repos/NoctideQY/NoobCoin/releases/latest"

    fun latestRelease(): GithubRelease {
        val connection = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NoobCoin/0.1")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 404) throw IllegalStateException("仓库暂时还没有发布 Release。")
            if (code !in 200..299) throw IllegalStateException("GitHub 更新接口失败（HTTP $code）。")
            val json = JSONObject(body)
            return GithubRelease(
                tagName = json.optString("tag_name"),
                name = json.optString("name").ifBlank { json.optString("tag_name") },
                body = json.optString("body"),
                htmlUrl = json.optString("html_url"),
                publishedAt = json.optString("published_at"),
            )
        } finally { connection.disconnect() }
    }
}
