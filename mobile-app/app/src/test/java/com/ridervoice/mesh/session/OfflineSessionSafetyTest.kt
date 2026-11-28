package com.ridervoice.mesh.session

import com.ridervoice.mesh.link.WifiDirectCredentialsFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSessionSafetyTest {

    @Test
    fun `derive WifiDirect credentials returns null instead of throwing when session is closed`() {
        val session = OfflineSession.create(now = 10_000L)
        assertFalse(session.isClosed)
        assertNotNull(WifiDirectCredentialsFactory.derive(session))

        session.close()
        assertTrue(session.isClosed)
        assertNull(WifiDirectCredentialsFactory.derive(session))
    }
}
