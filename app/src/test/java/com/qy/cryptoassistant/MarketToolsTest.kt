package com.qy.cryptoassistant

import org.junit.Assert.*
import org.junit.Test

class MarketToolsTest {
    private val rows = listOf(
        MarketTicker("AAA", "Alpha", "Layer 1", 10.0, -2.0, quoteVolume24h = 100.0),
        MarketTicker("BBB", "Beta", "Meme", 2.0, 5.0, quoteVolume24h = 300.0),
        MarketTicker("CCC", "Gamma", "Layer 1", 1.0, 0.0),
    )

    @Test fun gainersAndLosersExcludeFlatAndOppositeDirection() {
        assertEquals(listOf("BBB"), filterMarket(rows, "", "全部", MarketOrder.GAINERS).map { it.symbol })
        assertEquals(listOf("AAA"), filterMarket(rows, "", "全部", MarketOrder.LOSERS).map { it.symbol })
    }

    @Test fun volumeSortPutsMissingLast() {
        assertEquals(listOf("BBB", "AAA", "CCC"), filterMarket(rows, "", "全部", MarketOrder.VOLUME).map { it.symbol })
    }

    @Test fun searchAndCategoryCombine() {
        assertEquals(listOf("AAA"), filterMarket(rows, " alpha ", "Layer 1", MarketOrder.NAME).map { it.symbol })
        assertTrue(filterMarket(rows, "Alpha", "Meme", MarketOrder.DEFAULT).isEmpty())
    }

    @Test fun converterRejectsInvalidAndOverflowValues() {
        assertEquals(25.0, convertedQuantity("2.5", 10.0)!!, 0.00001)
        assertEquals(0.0, convertedQuantity("0", 10.0)!!, 0.00001)
        listOf("", "abc", "-1", "NaN", "Infinity", "1e309").forEach { assertNull(convertedQuantity(it, 1.0)) }
        assertNull(convertedQuantity("1", null))
        assertNull(convertedQuantity("1", 0.0))
        assertNull(convertedQuantity("1e308", 1e308))
    }
}
