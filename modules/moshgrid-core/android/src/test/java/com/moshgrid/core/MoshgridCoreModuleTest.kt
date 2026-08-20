package com.moshgrid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshgridCoreModuleTest {

    @Test
    fun `nativeInfo reports a module version`() {
        val info = NativeInfoProvider.describe(
            sdkInt = 34,
            bleSupported = true,
            extendedAdvertisingSupported = false,
        )
        assertEquals("0.1.0", info.moduleVersion)
        assertEquals(34, info.androidSdkInt)
        assertTrue(info.bleSupported)
    }
}
