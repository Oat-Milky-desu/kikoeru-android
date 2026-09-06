package app.kikoeru.android.ui

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/** CPU blur also works below Android 12, where Compose's blur modifier is a no-op. */
class PrivacyBlurTransformation : Transformation {
    override val cacheKey = "privacy-blur-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = 64
        val height = (64L * input.height / input.width).toInt().coerceIn(1, 128)
        val sampled = Bitmap.createScaledBitmap(input, width, height, true)
        var pixels = IntArray(width * height)
        sampled.getPixels(pixels, 0, width, 0, 0, width, height)
        // Three box passes approximate a Gaussian, erasing fine details and cover text.
        repeat(3) {
            for (horizontal in listOf(true, false)) {
                val output = IntArray(pixels.size)
                for (y in 0 until height) for (x in 0 until width) {
                    var red = 0; var green = 0; var blue = 0; var alpha = 0
                    for (offset in -6..6) {
                        val sx = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
                        val sy = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
                        val color = pixels[sy * width + sx]
                        alpha += color ushr 24; red += color shr 16 and 255
                        green += color shr 8 and 255; blue += color and 255
                    }
                    output[y * width + x] = (alpha / 13 shl 24) or (red / 13 shl 16) or (green / 13 shl 8) or (blue / 13)
                }
                pixels = output
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
