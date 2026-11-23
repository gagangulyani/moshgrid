package com.bitchat.android.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryGuideTest {
    @Test fun xiaomi_gets_autostart_step() {
        assertTrue(BatteryGuide.stepsFor("Xiaomi").any { it.contains("Autostart") })
        assertEquals(BatteryGuide.stepsFor("Xiaomi"), BatteryGuide.stepsFor("POCO"))
        assertEquals(BatteryGuide.stepsFor("xiaomi"), BatteryGuide.stepsFor("Redmi"))
    }
    @Test fun oneplus_oppo_realme_vivo_samsung_have_their_own_steps() {
        for (m in listOf("OnePlus", "OPPO", "realme", "vivo", "samsung")) {
            assertTrue(m, BatteryGuide.stepsFor(m) != BatteryGuide.stepsFor("unknown-brand"))
        }
    }
    @Test fun unknown_manufacturer_gets_generic_steps() {
        assertTrue(BatteryGuide.stepsFor("Acme").first().contains("Battery"))
    }
}
