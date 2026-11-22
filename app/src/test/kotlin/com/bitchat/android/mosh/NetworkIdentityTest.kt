package com.bitchat.android.mosh

import com.bitchat.android.nostr.NostrEmbeddedBitChat
import com.bitchat.android.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

/** MoshGrid runs as its own network: it must not discover or talk to Bitchat clients. */
class NetworkIdentityTest {

    @Test
    fun service_uuid_is_moshgrid_not_bitchat() {
        assertEquals(UUID.fromString("05651950-E122-467B-A40B-07439FEF3169"), AppConstants.Mesh.Gatt.SERVICE_UUID)
        assertEquals(UUID.fromString("239826BE-0EBA-49A3-8026-604B721652C1"), AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID)
        assertNotEquals(UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"), AppConstants.Mesh.Gatt.SERVICE_UUID)
    }

    @Test
    fun nostr_prefix_is_moshgrid() {
        assertEquals("moshgrid1:", NostrEmbeddedBitChat.PREFIX)
    }

    @Test
    fun verification_qr_scheme_is_moshgrid() {
        assertEquals("moshgrid", MoshBrand.QR_SCHEME)
    }
}
