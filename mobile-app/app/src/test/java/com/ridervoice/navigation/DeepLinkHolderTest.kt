package com.ridervoice.navigation

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DeepLinkHolderTest {

    @Before
    fun setUp() {
        DeepLinkHolder.consume()
    }

    @Test
    fun parseDeepLinkToken_customScheme() {
        val token = DeepLinkHolder.parseDeepLinkToken("ridervoice://join/abc123token")
        assertEquals("abc123token", token)
    }

    @Test
    fun parseDeepLinkToken_customSchemeWithQuery() {
        val token = DeepLinkHolder.parseDeepLinkToken("ridervoice://join?token=abc123token")
        assertEquals("abc123token", token)
    }

    @Test
    fun parseDeepLinkToken_httpsJPath() {
        val token = DeepLinkHolder.parseDeepLinkToken("https://ridervoice.onrender.com/j/token_xyz_456")
        assertEquals("token_xyz_456", token)
    }

    @Test
    fun parseDeepLinkToken_httpsJoinPath() {
        val token = DeepLinkHolder.parseDeepLinkToken("https://ridervoice.onrender.com/join/token_xyz_789")
        assertEquals("token_xyz_789", token)
    }

    @Test
    fun parseDeepLinkToken_returnsNullForInvalid() {
        assertNull(DeepLinkHolder.parseDeepLinkToken(null as String?))
        assertNull(DeepLinkHolder.parseDeepLinkToken(""))
        assertNull(DeepLinkHolder.parseDeepLinkToken("https://example.com/other/path"))
        assertNull(DeepLinkHolder.parseDeepLinkToken("ridervoice://other/route"))
    }

    @Test
    fun setAndConsume_managesState() {
        assertNull(DeepLinkHolder.pendingToken.value)
        DeepLinkHolder.set("test_token_123")
        assertEquals("test_token_123", DeepLinkHolder.pendingToken.value)

        val consumed = DeepLinkHolder.consume()
        assertEquals("test_token_123", consumed)
        assertNull(DeepLinkHolder.pendingToken.value)
        assertNull(DeepLinkHolder.consume())
    }
}
