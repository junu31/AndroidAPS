package app.aaps.ui.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Personal-fork helper.
 *
 * Loads an image from a content URI, downscales it to a max dimension suitable for the
 * Gemini multimodal API, re-encodes it as JPEG (which strips EXIF metadata such as GPS),
 * and returns a base64-encoded string ready to embed in an `inline_data` part.
 */
object ImageProcessor {

    private const val MAX_DIMENSION_PX = 1024
    private const val JPEG_QUALITY = 80

    data class EncodedImage(
        val base64: String,
        val mimeType: String = "image/jpeg",
        val sizeBytes: Int
    )

    fun encodeForGemini(context: Context, uri: Uri): EncodedImage {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val original = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.isMutableRequired = false
            val longestSide = max(info.size.width, info.size.height)
            if (longestSide > MAX_DIMENSION_PX) {
                val scale = MAX_DIMENSION_PX.toFloat() / longestSide
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
        return ByteArrayOutputStream().use { stream ->
            original.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            val bytes = stream.toByteArray()
            EncodedImage(
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                sizeBytes = bytes.size
            )
        }
    }
}
