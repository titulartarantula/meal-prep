package dev.mealprep.app.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.media.ExifInterface
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageScalerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `long edge capped at 2000, aspect kept, never upscaled`() {
        assertEquals(2000 to 1500, ImageScaler.targetSize(4000, 3000))
        assertEquals(1500 to 2000, ImageScaler.targetSize(3000, 4000))
        assertEquals(2000 to 1500, ImageScaler.targetSize(4032, 3024))
        assertEquals(1500 to 2000, ImageScaler.targetSize(6144, 8192))   // 50 MP portrait
        assertEquals(1200 to 900, ImageScaler.targetSize(1200, 900))
        assertEquals(2000 to 1, ImageScaler.targetSize(9000, 2))
    }

    /** A synthetic "page": white with dark text-like bars (no real cookbook content). */
    private fun page(w: Int, h: Int, format: Bitmap.CompressFormat, name: String): File =
        tmp.newFile(name).also { f -> f.outputStream().use { pageBitmap(w, h).compress(format, 90, it) } }

    private fun pageBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.DKGRAY }
        var y = h / 20
        while (y < h) { c.drawRect(w / 10f, y.toFloat(), w * 0.9f, y + h / 80f, p); y += h / 25 }
        return bmp
    }

    // Robolectric's native ImageDecoder isn't available on the Windows build host ("Not supported on Windows"):
    // the decode tests run only where it is (Linux/macOS). The JPEG step below runs everywhere.
    private fun assumeImageDecoder() =
        assumeFalse("native ImageDecoder unsupported on Windows", System.getProperty("os.name").orEmpty().startsWith("Windows"))

    @Test fun `a 2000 px page is written as a JPEG of reasonable size`() {
        val out = File(tmp.root, "page.jpg")
        ImageScaler.writeJpeg(pageBitmap(2000, 1500), out)
        val o = bounds(out)
        assertEquals(2000, o.outWidth); assertEquals(1500, o.outHeight)
        assertEquals("image/jpeg", o.outMimeType)
        assertTrue("${out.length()} bytes", out.length() in 1_000..1_500_000)
    }

    private fun bounds(f: File) = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }

    @Test fun `a big photo becomes a 2000 px JPEG`() {
        assumeImageDecoder()
        val src = page(4000, 3000, Bitmap.CompressFormat.PNG, "big.png")
        val out = File(tmp.root, "page.jpg")
        ImageScaler.toJpeg(ImageDecoder.createSource(src), out)
        val o = bounds(out)
        assertEquals(2000, o.outWidth); assertEquals(1500, o.outHeight)
        assertEquals("image/jpeg", o.outMimeType)
        assertTrue("${out.length()} bytes", out.length() < 1_500_000)
    }

    @Test fun `EXIF rotation is applied so the page uploads upright`() {
        assumeImageDecoder()
        val src = page(400, 300, Bitmap.CompressFormat.JPEG, "sideways.jpg")
        ExifInterface(src.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
        }
        val out = File(tmp.root, "upright.jpg")
        ImageScaler.toJpeg(ImageDecoder.createSource(src), out)
        val o = bounds(out)
        assertEquals(300, o.outWidth); assertEquals(400, o.outHeight)
    }
}
