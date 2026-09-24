package com.qy.cryptoassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceDataTest {
    @Test fun signatureIsDeterministic() {
        assertEquals(
            "c402d7b980cc9eabd875601df69f390fe7790d9ca1e140a3f62ec5f5d161e797",
            BinanceApi.hmacSha256("secret", "timestamp=1"),
        )
    }
}
