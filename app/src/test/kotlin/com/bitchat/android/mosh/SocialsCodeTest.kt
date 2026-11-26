package com.bitchat.android.mosh

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SocialsCodeTest {
    private val ig = "https://instagram.com/ana.moshes"
    private val sp = "https://open.spotify.com/user/ana"
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
    private fun code(vararg parts: String) = SocialsCode.PREFIX + "1." + parts.joinToString(".") { b64(it) }

    @Test fun golden_vector_matches_spec() {
        val golden = "https://moshgrid.vercel.app/s#1.QW5h.aHR0cHM6Ly9pbnN0YWdyYW0uY29tL2FuYS5tb3NoZXM.aHR0cHM6Ly9vcGVuLnNwb3RpZnkuY29tL3VzZXIvYW5h"
        assertEquals(golden, SocialsCode.encode("Ana", listOf(ig, sp)))
        assertEquals(SocialsCard("Ana", listOf(ig, sp)), SocialsCode.decode(golden))
    }

    @Test fun golden_vector_with_emoji_name_and_query() {
        val golden = "https://moshgrid.vercel.app/s#1.Sm8g8J-Opw.aHR0cHM6Ly9zb3VuZGNsb3VkLmNvbS9qby1vbi1zdGFnZT9yZWY9Z2ln"
        assertEquals(SocialsCard("Jo 🎧", listOf("https://soundcloud.com/jo-on-stage?ref=gig")), SocialsCode.decode(golden))
    }

    @Test fun empty_name_is_allowed() {
        val c = SocialsCode.encode("", listOf(ig))!!
        assertEquals(SocialsCard("", listOf(ig)), SocialsCode.decode(c))
    }

    @Test fun encode_drops_bad_links_and_duplicates_and_caps_at_six() {
        val many = (1..9).map { "https://example.com/p$it" }
        val c = SocialsCode.decode(SocialsCode.encode("Kai", listOf("http://x.com/a", ig, ig, "javascript:alert(1)") + many)!!)!!
        assertEquals(listOf(ig) + many.take(5), c.links)
        assertNull(SocialsCode.encode("Kai", listOf("ftp://x.com", "  ")))
    }

    @Test fun name_is_trimmed_cut_and_control_chars_removed() {
        val c = SocialsCode.decode(SocialsCode.encode("  Ana\u0007" + "x".repeat(60), listOf(ig))!!)!!
        assertEquals(SocialsCode.MAX_NAME, c.name.length)
        assertTrue(c.name.startsWith("Anax"))
    }

    @Test fun clean_link_rules() {
        assertEquals(ig, SocialsCode.cleanLink("  $ig  "))
        assertEquals(ig, SocialsCode.cleanLink("instagram.com/ana.moshes"))
        listOf(
            "http://instagram.com/a", "javascript:alert(1)", "https://", "https://localhost/a", "https://user@evil.example/x",
            "https://evil.example/a b", "https://evil.example/<script>", "https://evil.example/\"x", "https://exa mple.com",
            "https://example.com/" + "a".repeat(200), "https://-bad.com/", "https://example.c/", "HTTPS://example.com",
            "https://example.com\\@evil.com", "https://例え.jp/", "intent://scan#Intent;end", "data:text/html,x", "instagram", "file:///sdcard/x",
        ).forEach { assertNull(it, SocialsCode.cleanLink(it)) }
        assertNotNull(SocialsCode.cleanLink("https://www.youtube.com/@ana?si=abc#top"))
        assertNotNull(SocialsCode.cleanLink("https://example.com:8443/path"))
    }

    @Test fun decode_fails_closed() {
        listOf(
            "", "https://moshgrid.vercel.app/add#abc", SocialsCode.PREFIX, SocialsCode.PREFIX + "1",
            SocialsCode.PREFIX + "1." + b64("Ana"), // no links
            SocialsCode.PREFIX + "2." + b64("Ana") + "." + b64(ig), // unknown version
            code("Ana", "javascript:alert(1)"), code("Ana", "http://instagram.com/x"), code("Ana", "https://user@evil.example/x"),
            code("Ana", ig, ig), // repeated link
            code("Ana", *Array(7) { "https://example.com/$it" }), // too many
            code("x".repeat(41), ig), code(" Ana", ig), code("A\u0000na", ig),
            SocialsCode.PREFIX + "1." + b64("Ana") + ".aHR0c+Hz", // not base64url
            SocialsCode.PREFIX + "1." + b64("Ana") + "." + Base64.getUrlEncoder().encodeToString(ig.toByteArray()) + "=", // padding char
            SocialsCode.PREFIX + "1." + Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xC3.toByte(), 0x28)) + "." + b64(ig), // bad UTF-8
            SocialsCode.PREFIX + "1.." + b64(ig) + "." + "A".repeat(3000),
            "http://moshgrid.vercel.app/s#1." + b64("Ana") + "." + b64(ig),
        ).forEach { assertNull(it, SocialsCode.decode(it)) }
    }

    @Test fun labels_and_shown_addresses() {
        assertEquals("Instagram", SocialsCode.label("https://www.instagram.com/ana"))
        assertEquals("Spotify", SocialsCode.label(sp))
        assertEquals("YouTube", SocialsCode.label("https://m.youtube.com/@ana"))
        assertEquals("example.org", SocialsCode.label("https://Example.org:8443/x"))
        assertEquals("instagram.com/ana", SocialsCode.shown("https://www.instagram.com/ana/"))
    }

    @Test fun longest_code_still_fits_a_qr_and_reads_back() {
        val name = "🎧".repeat(20)
        val links = (1..6).map { i -> "https://example.com/" + "$i".repeat(200 - 20 - 1) }
        val c = SocialsCode.encode(name, links)!!
        assertTrue(c.length <= SocialsCode.MAX_CODE)
        assertEquals(SocialsCard(name, links), SocialsCode.decode(c))
        val m = QRCodeWriter().encode(c, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        assertTrue(m.width > 0)
    }

    @Test fun qr_round_trip_through_the_scanner_decoder() {
        val c = SocialsCode.encode("Ana", listOf(ig, sp))!!
        val m = QRCodeWriter().encode(c, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val scale = 4
        val w = m.width * scale
        val lum = ByteArray(w * w) { i -> if (m[(i % w) / scale, (i / w) / scale]) 0 else 255.toByte() }
        assertEquals(SocialsCard("Ana", listOf(ig, sp)), SocialsCode.decode(com.bitchat.android.ui.decodeQrLuminance(lum, w, w)!!))
    }
}
