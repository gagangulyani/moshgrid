package com.bitchat.android.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateManifestTest {
    private val sha = "ab".repeat(32)
    private fun json(code: Int = 6, url: String = "https://moshgrid.vercel.app/downloads/MoshGrid-1.2.0.apk", s: String = sha) = """
        {"versionCode": $code, "versionName": "1.2.0",
         "apk": {"arm64-v8a": {"url": "$url", "sha256": "$s"},
                 "universal": {"url": "https://moshgrid.vercel.app/downloads/MoshGrid-1.2.0-all-phones.apk", "sha256": "$sha"}}}
    """.trimIndent()

    @Test fun picks_the_apk_for_this_phone() {
        assertEquals("https://moshgrid.vercel.app/downloads/MoshGrid-1.2.0.apk", UpdateManifest.parse(json(), 5, listOf("arm64-v8a"))?.url)
        assertEquals("https://moshgrid.vercel.app/downloads/MoshGrid-1.2.0-all-phones.apk", UpdateManifest.parse(json(), 5, listOf("x86_64"))?.url)
        assertEquals(6, UpdateManifest.parse(json(), 5, listOf("arm64-v8a"))?.versionCode)
    }

    @Test fun nothing_when_not_newer_or_suspicious() {
        assertNull(UpdateManifest.parse(json(code = 5), 5, listOf("arm64-v8a")))
        assertNull(UpdateManifest.parse(json(url = "https://example.com/downloads/x.apk"), 5, listOf("arm64-v8a")))
        assertNull(UpdateManifest.parse(json(url = "http://moshgrid.vercel.app/downloads/x.apk"), 5, listOf("arm64-v8a")))
        assertNull(UpdateManifest.parse(json(s = "nothex"), 5, listOf("arm64-v8a")))
        assertNull(UpdateManifest.parse("not json", 5, listOf("arm64-v8a")))
    }
}
