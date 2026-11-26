package app.yehaw.browser.tools

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Thin wrapper over the ZXing QR reader. Only touched when the scanner (or its tests) runs; never at browser startup. */
object QrDecoder {
    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
        DecodeHintType.TRY_HARDER to true,
    )

    /** Decodes ARGB pixels. Returns null when no QR code is found (this is normal for most camera frames). */
    fun decodeArgb(pixels: IntArray, width: Int, height: Int): String? = decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels))))

    /** Decodes a camera preview frame in NV21/YUV format, looking at a centred square to keep CPU low on slow phones. */
    fun decodeYuv(data: ByteArray, width: Int, height: Int): String? {
        val side = (minOf(width, height) * 9) / 10
        val left = (width - side) / 2
        val top = (height - side) / 2
        val source = PlanarYUVLuminanceSource(data, width, height, left, top, side, side, false)
        return decode(BinaryBitmap(HybridBinarizer(source)))
    }

    private fun decode(bitmap: BinaryBitmap): String? = try {
        QRCodeReader().decode(bitmap, hints).text
    } catch (_: NotFoundException) {
        null
    } catch (_: com.google.zxing.ChecksumException) {
        null
    } catch (_: com.google.zxing.FormatException) {
        null
    }
}
