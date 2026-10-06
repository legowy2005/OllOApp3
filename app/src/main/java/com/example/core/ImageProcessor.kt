package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

data class ProcessedImageResult(
    val imageId: Long,
    val deviceWidth: Int,
    val deviceHeight: Int,
    val deviceData: ByteArray,
    val displayWidth: Int,
    val displayHeight: Int,
    val displayData: ByteArray,
    val checksum: Int,
    val isColor: Boolean = false
)

object ImageProcessor {

    data class ResolutionPreset(val name: String, val width: Int, val height: Int)

    val RESOLUTION_PRESETS = listOf(
        ResolutionPreset("320x240 (Min)", 320, 240),
        ResolutionPreset("400x300", 400, 300),
        ResolutionPreset("480x360", 480, 360),
        ResolutionPreset("640x400 (OLED native)", 640, 400),
        ResolutionPreset("640x480 (Max)", 640, 480)
    )

    /**
     * Decode an image and normalize the phone/camera EXIF orientation immediately.
     *
     * This fixes the common case where the JPEG's pixels are stored sideways while
     * the camera relies on the EXIF orientation tag to tell viewers how to display it.
     */
    fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int = DeviceLimits.EDITOR_MAX_W,
        reqHeight: Int = DeviceLimits.EDITOR_MAX_H
    ): Bitmap? {
        return try {
            val orientation = readExifOrientation(context, uri)

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri).use { stream ->
                if (stream != null) {
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return null
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            val decoded = context.contentResolver.openInputStream(uri).use { stream ->
                if (stream != null) {
                    BitmapFactory.decodeStream(stream, null, options)
                } else {
                    null
                }
            } ?: return null

            applyExifOrientation(decoded, orientation)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun readExifOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri).use { stream ->
                if (stream == null) {
                    ExifInterface.ORIENTATION_NORMAL
                } else {
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * Handles the common camera orientations explicitly.
     * The remaining mirror variants are also covered so the stored bitmap is upright.
     */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return bitmap
        }

        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.setScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.setRotate(180f)
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setScale(1f, -1f)
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> {
                matrix.setRotate(90f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                matrix.setRotate(-90f)
            }

            else -> return bitmap
        }

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (
                halfHeight / inSampleSize >= reqHeight &&
                halfWidth / inSampleSize >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }

        return max(1, inSampleSize)
    }

    fun createSampleGraphic(width: Int = 320, height: Int = 240): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = true }

        canvas.drawColor(AndroidColor.WHITE)

        val cx = width / 2f
        val cy = height / 2f
        val eyeRadius = min(width, height) * 0.22f

        paint.color = AndroidColor.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(4f, min(width, height) * 0.035f)

        canvas.drawCircle(cx - eyeRadius * 1.3f, cy, eyeRadius, paint)
        canvas.drawCircle(cx + eyeRadius * 1.3f, cy, eyeRadius, paint)
        canvas.drawLine(cx - eyeRadius * 0.3f, cy, cx + eyeRadius * 0.3f, cy, paint)
        canvas.drawLine(cx - eyeRadius * 2.3f, cy, cx - eyeRadius * 2.7f, cy - eyeRadius * 0.4f, paint)
        canvas.drawLine(cx + eyeRadius * 2.3f, cy, cx + eyeRadius * 2.7f, cy - eyeRadius * 0.4f, paint)

        paint.style = Paint.Style.FILL
        paint.textSize = max(18f, min(width, height) * 0.12f)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("OllO", cx, cy + eyeRadius * 1.7f, paint)

        return bitmap
    }

    fun cropBitmap(
        source: Bitmap,
        leftRatio: Float = 0f,
        topRatio: Float = 0f,
        rightRatio: Float = 1f,
        bottomRatio: Float = 1f
    ): Bitmap {
        val left = (source.width * leftRatio.coerceIn(0f, 0.9f)).toInt()
        val top = (source.height * topRatio.coerceIn(0f, 0.9f)).toInt()

        val width = (source.width * (rightRatio - leftRatio).coerceIn(0.1f, 1f))
            .toInt()
            .coerceAtMost(source.width - left)

        val height = (source.height * (bottomRatio - topRatio).coerceIn(0.1f, 1f))
            .toInt()
            .coerceAtMost(source.height - top)

        return Bitmap.createBitmap(
            source,
            left,
            top,
            max(1, width),
            max(1, height)
        )
    }

    /**
     * OllO's actual stored image format:
     *   - black/white only
     *   - 1 bit per pixel
     *   - row-major
     *   - MSB is the left-most pixel
     *
     * There is intentionally NO Floyd-Steinberg dithering in the device path.
     * This avoids halftone noise and keeps text/strokes crisp.
     *
     * isColorMode is retained in the API for source compatibility with the current UI,
     * but new images are always saved as 1-bit monochrome.
     */
    @Suppress("UNUSED_PARAMETER")
    fun processImage(
        sourceBitmap: Bitmap,
        targetWidth: Int = 320,
        targetHeight: Int = 240,
        rotationDegrees: Float = 0f,
        flipHorizontal: Boolean = false,
        flipVertical: Boolean = false,
        cropLeftRatio: Float = 0f,
        cropTopRatio: Float = 0f,
        cropRightRatio: Float = 1f,
        cropBottomRatio: Float = 1f,
        useDithering: Boolean = false,
        threshold: Int = 128,
        invert: Boolean = false,
        isColorMode: Boolean = false,
        backgroundColorArgb: Int = AndroidColor.WHITE
    ): ProcessedImageResult {
        val clampedTargetW = targetWidth.coerceIn(
            DeviceLimits.EDITOR_MIN_W,
            DeviceLimits.EDITOR_MAX_W
        )
        val clampedTargetH = targetHeight.coerceIn(
            DeviceLimits.EDITOR_MIN_H,
            DeviceLimits.EDITOR_MAX_H
        )

        // User-controlled orientation transforms.
        val matrix = Matrix()

        if (rotationDegrees != 0f) {
            matrix.postRotate(rotationDegrees)
        }

        val sx = if (flipHorizontal) -1f else 1f
        val sy = if (flipVertical) -1f else 1f

        if (flipHorizontal || flipVertical) {
            matrix.postScale(sx, sy)
        }

        val orientedBitmap = Bitmap.createBitmap(
            sourceBitmap,
            0,
            0,
            sourceBitmap.width,
            sourceBitmap.height,
            matrix,
            true
        )

        val croppedBitmap = if (
            cropLeftRatio > 0f ||
            cropTopRatio > 0f ||
            cropRightRatio < 1f ||
            cropBottomRatio < 1f
        ) {
            cropBitmap(
                orientedBitmap,
                cropLeftRatio,
                cropTopRatio,
                cropRightRatio,
                cropBottomRatio
            )
        } else {
            orientedBitmap
        }

        /*
         * Preserve the source aspect ratio.
         *
         * The old path stretched every source to targetWidth x targetHeight.
         * A phone photo can therefore be distorted even when the source itself
         * is perfectly fine. The new path letterboxes onto a white canvas instead.
         */
        val displayBitmap = fitBitmapToCanvas(
            source = croppedBitmap,
            canvasWidth = clampedTargetW,
            canvasHeight = clampedTargetH,
            backgroundColorArgb = backgroundColorArgb
        )

        val limitW = DeviceLimits.deviceMaxW
        val limitH = DeviceLimits.deviceMaxH

        val (deviceW, deviceH) = calculateFitDimensions(
            clampedTargetW,
            clampedTargetH,
            limitW,
            limitH
        )

        val deviceBitmap = if (
            deviceW == displayBitmap.width &&
            deviceH == displayBitmap.height
        ) {
            displayBitmap
        } else {
            Bitmap.createScaledBitmap(
                displayBitmap,
                deviceW,
                deviceH,
                true
            )
        }

        val devicePixels = IntArray(deviceW * deviceH)
        deviceBitmap.getPixels(
            devicePixels,
            0,
            deviceW,
            0,
            0,
            deviceW,
            deviceH
        )

        // Always produce packed 1-bit data.
        val packedBytes = threshold1Bit(
            pixels = devicePixels,
            width = deviceW,
            height = deviceH,
            threshold = threshold,
            invert = invert
        )

        var checksumSum = 0
        for (b in packedBytes) {
            checksumSum += (b.toInt() and 0xFF)
        }
        val checksum = checksumSum and 0xFF

        val imageId = Crc32.calculateImageId(
            deviceW,
            deviceH,
            packedBytes
        )

        return ProcessedImageResult(
            imageId = imageId,
            deviceWidth = deviceW,
            deviceHeight = deviceH,
            deviceData = packedBytes,
            /*
             * Keep the phone-side preview PNG for the existing UI.
             * It is not sent to the glasses.
             */
            displayWidth = displayBitmap.width,
            displayHeight = displayBitmap.height,
            displayData = bitmapToPng(displayBitmap),
            checksum = checksum,
            isColor = false
        )
    }

    /**
     * Draw the image onto the selected canvas without changing its aspect ratio.
     */
    private fun fitBitmapToCanvas(
        source: Bitmap,
        canvasWidth: Int,
        canvasHeight: Int,
        backgroundColorArgb: Int
    ): Bitmap {
        val output = Bitmap.createBitmap(
            canvasWidth,
            canvasHeight,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(output)
        canvas.drawColor(backgroundColorArgb)

        val scale = min(
            canvasWidth.toFloat() / source.width.toFloat(),
            canvasHeight.toFloat() / source.height.toFloat()
        )

        val drawWidth = source.width * scale
        val drawHeight = source.height * scale

        val left = (canvasWidth - drawWidth) * 0.5f
        val top = (canvasHeight - drawHeight) * 0.5f

        val dest = RectF(
            left,
            top,
            left + drawWidth,
            top + drawHeight
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, dest, paint)

        return output
    }

    private fun bitmapToPng(bitmap: Bitmap): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(
            Bitmap.CompressFormat.PNG,
            100,
            output
        )
        return output.toByteArray()
    }

    /**
     * Simple, deterministic B&W conversion.
     *
     * No dithering and no adaptive gray stage:
     *   luma >= threshold -> white
     *   luma <  threshold -> black
     *
     * That produces the exact 1-bit pixels that are stored in Room and later sent
     * to the ESP32 without another image conversion step.
     */
    private fun threshold1Bit(
        pixels: IntArray,
        width: Int,
        height: Int,
        threshold: Int,
        invert: Boolean
    ): ByteArray {
        val rowBytes = (width + 7) / 8
        val packed = ByteArray(rowBytes * height)
        val cutoff = threshold.coerceIn(0, 255)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]

                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val luminance =
                    (299 * r + 587 * g + 114 * b) / 1000

                var white = luminance >= cutoff
                if (invert) {
                    white = !white
                }

                if (white) {
                    val byteIndex = y * rowBytes + (x / 8)
                    val bitOffset = 7 - (x % 8)
                    packed[byteIndex] =
                        (packed[byteIndex].toInt() or (1 shl bitOffset)).toByte()
                }
            }
        }

        return packed
    }

    /**
     * Kept for compatibility with old callers/files. New processing never uses it.
     */
    private fun packRgb565(pixels: IntArray): ByteArray {
        val out = ByteArray(pixels.size * 2)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            val v =
                ((r shr 3) shl 11) or
                ((g shr 2) shl 5) or
                (b shr 3)

            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }

        return out
    }

    fun createPreviewBitmap(
        data: ByteArray,
        width: Int,
        height: Int,
        format: Int
    ): Bitmap =
        if (format == 0) {
            createOledPreviewBitmap(data, width, height)
        } else {
            createRgb565PreviewBitmap(data, width, height)
        }

    /**
     * Legacy RGB565 preview support for already-stored images.
     * New images are always format 0.
     */
    fun createRgb565PreviewBitmap(
        data: ByteArray,
        width: Int,
        height: Int
    ): Bitmap {
        val argb = IntArray(width * height)

        for (i in argb.indices) {
            if (i * 2 + 1 >= data.size) break

            val v =
                (data[i * 2].toInt() and 0xFF) or
                ((data[i * 2 + 1].toInt() and 0xFF) shl 8)

            val r = ((v shr 11) and 0x1F) * 255 / 31
            val g = ((v shr 5) and 0x3F) * 255 / 63
            val b = (v and 0x1F) * 255 / 31

            argb[i] =
                (0xFF shl 24) or
                (r shl 16) or
                (g shl 8) or
                b
        }

        val bmp = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        bmp.setPixels(
            argb,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return bmp
    }

    private fun calculateFitDimensions(
        w: Int,
        h: Int,
        maxW: Int,
        maxH: Int
    ): Pair<Int, Int> {
        if (w <= maxW && h <= maxH) {
            return Pair(w, h)
        }

        val ratioW = maxW.toFloat() / w.toFloat()
        val ratioH = maxH.toFloat() / h.toFloat()
        val scale = min(ratioW, ratioH)

        val fitW = (w * scale)
            .toInt()
            .coerceIn(1, maxW)

        val fitH = (h * scale)
            .toInt()
            .coerceIn(1, maxH)

        return Pair(fitW, fitH)
    }

    /**
     * Decode packed 1-bit data to an Android bitmap.
     * Bit layout matches the ESP32/RP2040 protocol:
     * MSB is the left-most pixel.
     */
    fun createOledPreviewBitmap(
        packedBytes: ByteArray,
        width: Int,
        height: Int
    ): Bitmap {
        val rowBytes = (width + 7) / 8
        val argbPixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val byteIdx = y * rowBytes + (x / 8)

                if (byteIdx < packedBytes.size) {
                    val bit =
                        (packedBytes[byteIdx].toInt() shr (7 - (x % 8))) and 1

                    argbPixels[y * width + x] =
                        if (bit == 1) {
                            -0x1
                        } else {
                            -0x1000000
                        }
                }
            }
        }

        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        bitmap.setPixels(
            argbPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return bitmap
    }

    fun createOledPreviewImageBitmap(
        packedBytes: ByteArray,
        width: Int,
        height: Int
    ): ImageBitmap {
        return createOledPreviewBitmap(
            packedBytes,
            width,
            height
        ).asImageBitmap()
    }
}
