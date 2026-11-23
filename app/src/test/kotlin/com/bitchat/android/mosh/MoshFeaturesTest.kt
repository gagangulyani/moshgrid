package com.bitchat.android.mosh

import org.junit.Assert.assertFalse
import org.junit.Test

class MoshFeaturesTest {
    @Test
    fun all_non_v1_features_are_off() {
        assertFalse(MoshFeatures.PUBLIC_CHAT); assertFalse(MoshFeatures.VOICE); assertFalse(MoshFeatures.MEDIA)
        assertFalse(MoshFeatures.GLOBE); assertFalse(MoshFeatures.LOCATION_NOTES); assertFalse(MoshFeatures.CASHU)
        assertFalse(MoshFeatures.GEOHASH)
    }
}
