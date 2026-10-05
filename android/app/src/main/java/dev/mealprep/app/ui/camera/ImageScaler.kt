package dev.mealprep.app.ui.camera

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import kotlin.math.roundToInt

/** Phone photos are 12–50 MP; the vision model reads pages at ~1.5k px. A 2000 px JPEG keeps text sharp and
 *  keeps a page around 0.5–1.5 MB, so ten pages upload in seconds on home Wi-Fi. */
object ImageScaler {
    const val MAX_EDGE = 2000
    const val QUALITY = 85

    fun targetSize(w: Int, h: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= maxEdge) return w to h
        val s = maxEdge.toDouble() / long
        return maxOf(1, (w * s).roundToInt()) to maxOf(1, (h * s).roundToInt())
    }

    /** Decodes [source] straight at the target size (never the full 50 MP bitmap) and writes a JPEG to [out].
     *  ImageDecoder applies the EXIF orientation, so a portrait page shared from Photos uploads upright. */
    fun toJpeg(source: ImageDecoder.Source, out: File, maxEdge: Int = MAX_EDGE, quality: Int = QUALITY) {
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = targetSize(info.size.width, info.size.height, maxEdge)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            writeJpeg(bmp, out, quality)
        } finally {
            bmp.recycle()
        }
    }

    fun writeJpeg(bmp: Bitmap, out: File, quality: Int = QUALITY) {
        out.outputStream().use { check(bmp.compress(Bitmap.CompressFormat.JPEG, quality, it)) { "JPEG encoding failed" } }
    }
}
