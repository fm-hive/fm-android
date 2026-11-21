package lan.fmnode.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Image bytes after optional downscaling/compression at insertion time. */
data class ProcessedImage(
    val name: String,
    val type: String,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

/**
 * Downscales a picked image to [maxDimension] and re-encodes it, matching the
 * fm-app behaviour: images below the limit are kept as-is, PNG transparency is
 * preserved, photos become JPEG.
 */
object ImageProcessor {
    fun process(
        bytes: ByteArray,
        name: String,
        mime: String,
        maxDimension: Int,
        quality: Int = 85,
    ): ProcessedImage {
        if (!mime.startsWith("image/") || bytes.isEmpty()) {
            return ProcessedImage(name, mime, bytes, 0, 0)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return ProcessedImage(name, mime, bytes, 0, 0)
        }
        if (maxDimension <= 0 || (sourceWidth <= maxDimension && sourceHeight <= maxDimension)) {
            return ProcessedImage(name, mime, bytes, sourceWidth, sourceHeight)
        }

        val sample = sampleSize(sourceWidth, sourceHeight, maxDimension)
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return ProcessedImage(name, mime, bytes, sourceWidth, sourceHeight)
        val oriented = applyExif(bytes, decoded)
        val scale = min(
            maxDimension.toFloat() / oriented.width,
            maxDimension.toFloat() / oriented.height,
        ).coerceAtMost(1f)
        val width = max(1, (oriented.width * scale).roundToInt())
        val height = max(1, (oriented.height * scale).roundToInt())
        val scaled = if (width == oriented.width && height == oriented.height) {
            oriented
        } else {
            Bitmap.createScaledBitmap(oriented, width, height, true)
        }
        val png = mime.equals("image/png", ignoreCase = true)
        val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val out = ByteArrayOutputStream()
        scaled.compress(format, quality, out)
        val resultType = if (png) "image/png" else "image/jpeg"
        val resultName = replaceExtension(name, if (png) "png" else "jpg")
        val resultBytes = out.toByteArray()
        if (resultBytes.isEmpty() || resultBytes.size >= bytes.size) {
            // Do not make a small file larger; keep the original dimensions when
            // re-encoding did not help.
            if (scaled !== oriented) scaled.recycle()
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
            return ProcessedImage(name, mime, bytes, sourceWidth, sourceHeight)
        }
        if (scaled !== oriented) scaled.recycle()
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
        return ProcessedImage(resultName, resultType, resultBytes, width, height)
    }

    private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxDimension && h / 2 >= maxDimension) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun applyExif(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun replaceExtension(name: String, extension: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) + "." + extension else "$name.$extension"
    }
}
