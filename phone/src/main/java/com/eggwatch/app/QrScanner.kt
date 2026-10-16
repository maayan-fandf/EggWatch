package com.eggwatch.app

import android.graphics.Bitmap
import com.eggwatch.shared.QrModules
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.DetectorResult
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.detector.MultiDetector
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.QRCodeDecoderMetaData
import com.google.zxing.qrcode.detector.Detector
import com.google.zxing.qrcode.encoder.Encoder
import java.security.MessageDigest

/**
 * Finds a QR code in a screenshot and returns its exact module grid.
 *
 * We sample the grid ourselves (Detector) instead of using a high-level
 * reader, because the watch should show the very same modules the Egged app
 * drew — not a re-encoding of the decoded text, which could alter binary data.
 */
object QrScanner {

    class Hit(
        val modules: QrModules,
        val text: String,
        /** Short SHA-256 of the raw data codewords; identifies the code exactly. */
        val hash: String,
        val errorsCorrected: Int,
    )

    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun scan(bitmap: Bitmap): Hit? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val matrix = try {
            HybridBinarizer(RGBLuminanceSource(w, h, pixels)).blackMatrix
        } catch (_: ReaderException) {
            return null
        }
        find(matrix)?.let { return it }
        matrix.flip() // light-on-dark codes
        return find(matrix)
    }

    /** Test code for checking the phone → watch path without a real ticket. */
    fun encodeTest(text: String): QrModules {
        val m = Encoder.encode(text, ErrorCorrectionLevel.M).matrix
        return QrModules(m.width, BooleanArray(m.width * m.height) { m.get(it % m.width, it / m.width).toInt() == 1 })
    }

    private fun find(matrix: BitMatrix): Hit? {
        tryDecode { Detector(matrix).detect(hints) }?.let { return it }
        // Several QR-like shapes on screen (e.g. the QR icon in Egged's tab bar)
        // can mislead the single-code detector; try each candidate instead.
        val candidates = try {
            MultiDetector(matrix).detectMulti(hints)
        } catch (_: ReaderException) {
            return null
        }
        return candidates
            .sortedByDescending { it.bits.height }
            .firstNotNullOfOrNull { candidate -> tryDecode { candidate } }
    }

    private inline fun tryDecode(detect: () -> DetectorResult): Hit? = try {
        val grid = detect().bits
        val size = grid.height
        // Decoder unmasks the grid in place, so copy the modules first.
        val dark = BooleanArray(size * size) { grid[it % size, it / size] }
        val result = Decoder().decode(grid, hints)
        // A mirrored code would need flipping before display; phones never draw one.
        if ((result.other as? QRCodeDecoderMetaData)?.isMirrored == true) null
        else Hit(
            modules = QrModules(size, dark),
            text = result.text,
            hash = shortHash(result.rawBytes ?: result.text.toByteArray()),
            errorsCorrected = result.errorsCorrected ?: 0,
        )
    } catch (_: ReaderException) {
        null
    }

    private fun shortHash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(6).joinToString("") { "%02x".format(it) }
}
