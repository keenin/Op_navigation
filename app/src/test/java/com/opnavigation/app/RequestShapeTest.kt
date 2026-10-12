package com.opnavigation.app

import com.opnavigation.app.data.CommaClient
import com.opnavigation.app.data.describeFailure
import com.opnavigation.app.data.httpFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

private class Silent : RuntimeException()

class RequestShapeTest {
    @Test
    fun athenaUrlHasNoTrailingSlash() {
        assertEquals(
            "https://athena.comma.ai/0123456789abcdef",
            CommaClient.athenaUrl("0123456789ABCDEF"),
        )
        assertEquals(
            "https://athena.comma.ai/0123456789abcdef",
            CommaClient.athenaUrl("  /0123456789abcdef/  "),
        )
        assertFalse(CommaClient.athenaUrl("0123456789abcdef").endsWith("/"))
    }

    @Test
    fun tokenDropsJwtPrefixAndWhitespace() {
        assertEquals("eyJabc.def", CommaClient.normalizeToken("JWT eyJabc.def"))
        assertEquals("eyJabc.def", CommaClient.normalizeToken("\"jwt\neyJabc.def\""))
        assertEquals("eyJabc.def", CommaClient.normalizeToken("  eyJabc.\ndef  "))
    }

    @Test
    fun failureTextKeepsTheExceptionAndHttpBody() {
        val error = IllegalStateException("network error", java.io.IOException("connection reset"))
        assertEquals(
            "IllegalStateException: network error | IOException: connection reset",
            describeFailure(error),
        )
        assertEquals("Silent", describeFailure(Silent()))
        assertEquals(
            "Device is offline (HTTP 400: {\"error\":\"not connected\"})",
            httpFailure("Device is offline", 400, "{\"error\":\"not connected\"}"),
        )
    }
}
