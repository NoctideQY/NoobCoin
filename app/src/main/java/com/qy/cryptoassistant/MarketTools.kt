package com.qy.cryptoassistant

enum class MarketOrder(val label: String) {
    DEFAULT("默认"), VOLUME("成交额"), GAINERS("涨幅榜"), LOSERS("跌幅榜"), NAME("名称 A-Z")
}

fun filterMarket(tickers: List<MarketTicker>, query: String, category: String, order: MarketOrder): List<MarketTicker> {
    val matches = tickers.filter {
        (category == "全部" || it.category == category) &&
            (it.symbol.contains(query.trim(), true) || it.name.contains(query.trim(), true))
    }
    return when (order) {
        MarketOrder.DEFAULT -> matches
        MarketOrder.VOLUME -> matches.sortedByDescending { it.quoteVolume24h ?: -1.0 }
        MarketOrder.GAINERS -> matches.filter { it.changePercent > 0 }.sortedByDescending { it.changePercent }
        MarketOrder.LOSERS -> matches.filter { it.changePercent < 0 }.sortedBy { it.changePercent }
        MarketOrder.NAME -> matches.sortedBy { it.symbol }
    }
}

fun convertedQuantity(input: String, price: Double?): Double? {
    val quantity = input.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
    val validPrice = price?.takeIf { it.isFinite() && it > 0 } ?: return null
    return (quantity * validPrice).takeIf { it.isFinite() }
}
