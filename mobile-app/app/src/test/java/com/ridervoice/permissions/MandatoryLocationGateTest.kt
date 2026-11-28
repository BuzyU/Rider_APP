package com.ridervoice.permissions

import android.app.Application
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MandatoryLocationGateTest {

    @Test
    fun `evaluateStatus enforces permission first then active location services`() {
        assertEquals(
            LocationGateStatus.MISSING_PERMISSION,
            MandatoryLocationGate.evaluateStatus(hasPermission = false, locationEnabled = false)
        )
        assertEquals(
            LocationGateStatus.MISSING_PERMISSION,
            MandatoryLocationGate.evaluateStatus(hasPermission = false, locationEnabled = true)
        )
        assertEquals(
            LocationGateStatus.LOCATION_SERVICES_OFF,
            MandatoryLocationGate.evaluateStatus(hasPermission = true, locationEnabled = false)
        )
        assertEquals(
            LocationGateStatus.READY,
            MandatoryLocationGate.evaluateStatus(hasPermission = true, locationEnabled = true)
        )
    }

    @Test
    fun `buildLocationSettingsIntent targets location source settings`() {
        val intent = MandatoryLocationGate.buildLocationSettingsIntent()
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, intent.action)
    }
}
