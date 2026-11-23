package com.bitchat.android.mosh

import com.bitchat.android.ui.decodeQrLuminance
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The friend QR scanner decodes with ZXing (open source), not ML Kit. */
class QrDecodeTest {
    private val text = "moshgrid://verify?synthetic=1"

    private fun frame(rotate90: Boolean = false, invert: Boolean = false): Triple<ByteArray, Int, Int> {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 320, 320)
        val w = m.width; val h = m.height
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val (sx, sy) = if (rotate90) Pair(y, w - 1 - x) else Pair(x, y)
            val dark = m[sx, sy] != invert
            out[y * w + x] = (if (dark) 0 else 255).toByte()
        }
        return Triple(out, w, h)
    }

    @Test fun decodes_upright() {
        val (f, w, h) = frame(); assertEquals(text, decodeQrLuminance(f, w, h))
    }

    @Test fun decodes_rotated() {
        val (f, w, h) = frame(rotate90 = true); assertEquals(text, decodeQrLuminance(f, w, h))
    }

    @Test fun decodes_inverted_screen() {
        val (f, w, h) = frame(invert = true); assertEquals(text, decodeQrLuminance(f, w, h))
    }

    @Test fun blank_frame_is_null() {
        assertNull(decodeQrLuminance(ByteArray(100 * 100) { 127 }, 100, 100))
    }
}
