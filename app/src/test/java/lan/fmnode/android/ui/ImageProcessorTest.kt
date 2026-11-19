package lan.fmnode.android.ui

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageProcessorTest {
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(42)
        val pixels = IntArray(width * height) { random.nextInt() }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun `large image is downscaled to the selected dimension`() {
        val result = ImageProcessor.process(png(2000, 1000), "photo.png", "image/png", 640)
        assertTrue(result.width <= 640)
        assertTrue(result.height <= 640)
        assertEquals(640, result.width)
        assertEquals(320, result.height)
        assertTrue(result.bytes.isNotEmpty())
    }

    @Test
    fun `small image keeps its original bytes`() {
        val original = png(100, 50)
        val result = ImageProcessor.process(original, "small.png", "image/png", 1280)
        assertArrayEquals(original, result.bytes)
    }

    @Test
    fun `non-image files are left untouched`() {
        val data = "hello".toByteArray()
        val result = ImageProcessor.process(data, "a.txt", "text/plain", 1280)
        assertArrayEquals(data, result.bytes)
        assertEquals("text/plain", result.type)
    }
}
