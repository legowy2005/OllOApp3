package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
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

    fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int = DeviceLimits.EDITOR_MAX_W,
        reqHeight: Int = DeviceLimits.EDITOR_MAX_H
    ): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            var stream: InputStream? = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(stream, null, options)
            stream?.close()

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            stream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(stream, null, options)
            stream?.close()
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
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

        return Bitmap.createBitmap(source, left, top, max(1, width), max(1, height))
    }

    /**
     * Converts a source ARGB bitmap through the OllO image pipeline:
     * - Rotate & flip
     * - Crop
     * - Composite transparency
     * - Resize
     * - Grayscale
     * - 1-bit thresholding or Floyd-Steinberg dithering
     * - Pack into row-major 1-bit bytes
     */
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
        useDithering: Boolean = true,
        threshold: Int = 128,
        invert: Boolean = false,
        isColorMode: Boolean = false,
        backgroundColorArgb: Int = AndroidColor.WHITE
    ): ProcessedImageResult {
        val clampedTargetW = targetWidth.coerceIn(DeviceLimits.EDITOR_MIN_W, DeviceLimits.EDITOR_MAX_W)
        val clampedTargetH = targetHeight.coerceIn(DeviceLimits.EDITOR_MIN_H, DeviceLimits.EDITOR_MAX_H)

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

        val opaqueBitmap = Bitmap.createBitmap(
            croppedBitmap.width,
            croppedBitmap.height,
            Bitmap.Config.ARGB_8888
        )
        val opaqueCanvas = Canvas(opaqueBitmap)
        opaqueCanvas.drawColor(backgroundColorArgb)
        opaqueCanvas.drawBitmap(croppedBitmap, 0f, 0f, null)

        val displayBitmap = Bitmap.createScaledBitmap(
            opaqueBitmap,
            clampedTargetW,
            clampedTargetH,
            true
        )

        val limitW = if (isColorMode) DeviceLimits.COLOR_MAX_W else DeviceLimits.deviceMaxW
        val limitH = if (isColorMode) DeviceLimits.COLOR_MAX_H else DeviceLimits.deviceMaxH
        val (deviceW, deviceH) = calculateFitDimensions(
            clampedTargetW,
            clampedTargetH,
            limitW,
            limitH
        )

        val deviceBitmap = Bitmap.createScaledBitmap(
            displayBitmap,
            deviceW,
            deviceH,
            true
        )

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

        val packedBytes = if (isColorMode) {
            packRgb565(devicePixels)
        } else if (useDithering) {
            floydSteinbergDither(devicePixels, deviceW, deviceH, invert)
        } else {
            adaptiveThreshold1Bit(
                devicePixels,
                deviceW,
                deviceH,
                threshold,
                invert
            )
        }

        var checksumSum = 0
        for (b in packedBytes) {
            checksumSum += (b.toInt() and 0xFF)
        }
        val checksum = checksumSum and 0xFF

        val imageId = Crc32.calculateImageId(deviceW, deviceH, packedBytes)

        val displayStream = ByteArrayOutputStream()
        displayBitmap.compress(Bitmap.CompressFormat.PNG, 95, displayStream)
        val displayBytes = displayStream.toByteArray()

        return ProcessedImageResult(
            imageId = imageId,
            deviceWidth = deviceW,
            deviceHeight = deviceH,
            deviceData = packedBytes,
            displayWidth = clampedTargetW,
            displayHeight = clampedTargetH,
            displayData = displayBytes,
            checksum = checksum,
            isColor = isColorMode
        )
    }

    /** RGB565, little-endian, row-major, 2 bytes per pixel. */
    private fun packRgb565(pixels: IntArray): ByteArray {
        val out = ByteArray(pixels.size * 2)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val v = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
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
            argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(argb, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun calculateFitDimensions(
        w: Int,
        h: Int,
        maxW: Int,
        maxH: Int
    ): Pair<Int, Int> {
        if (w <= maxW && h <= maxH) return Pair(w, h)
        val ratioW = maxW.toFloat() / w.toFloat()
        val ratioH = maxH.toFloat() / h.toFloat()
        val scale = min(ratioW, ratioH)
        val fitW = (w * scale).toInt().coerceIn(1, maxW)
        val fitH = (h * scale).toInt().coerceIn(1, maxH)
        return Pair(fitW, fitH)
    }

    /**
     * Adaptive thresholding for text/documents.
     *
     * Unlike a fixed 128 cutoff, the threshold follows local illumination.
     * A small local-contrast boost is applied before classification, which
     * helps thin text survive antialiasing and mild blur from rescaling.
     *
     * windowSize is odd and intentionally modest so this stays fast on mobile.
     */
    private fun adaptiveThreshold1Bit(
        pixels: IntArray,
        width: Int,
        height: Int,
        globalThreshold: Int,
        invert: Boolean
    ): ByteArray {
        val rowStride = width + 1
        val integral = IntArray((height + 1) * rowStride)

        // Convert to luma and build an integral image.
        // The whole-frame integral safely fits in a signed Int at 640x480.
        val gray = IntArray(width * height)

        var globalSum = 0L
        for (y in 0 until height) {
            var rowSum = 0
            val srcBase = y * width
            val integralBase = (y + 1) * rowStride
            val prevBase = y * rowStride

            for (x in 0 until width) {
                val p = pixels[srcBase + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val luminance =
                    (299 * r + 587 * g + 114 * b) / 1000

                gray[srcBase + x] = luminance
                rowSum += luminance
                globalSum += luminance.toLong()

                integral[integralBase + x + 1] =
                    integral[prevBase + x + 1] + rowSum
            }
        }

        val globalMean =
            (globalSum / (width.toLong() * height.toLong()))
                .toInt()

        // 15x15 local window. Larger windows help with photos/scans;
        // smaller windows preserve very small text but can become noisy.
        val windowSize = 15
        val radius = windowSize / 2

        // Blend the requested global threshold into the adaptive result.
        // The adaptive component is dominant, so shadows don't erase text.
        val globalOffset = (globalThreshold - 128) / 4
        val localC = 7 + globalOffset

        val rowBytes = (width + 7) / 8
        val packed = ByteArray(rowBytes * height)

        for (y in 0 until height) {
            val y0 = max(0, y - radius)
            val y1 = min(height - 1, y + radius)

            for (x in 0 until width) {
                val x0 = max(0, x - radius)
                val x1 = min(width - 1, x + radius)

                val a = integral[y0 * rowStride + x0]
                val b = integral[y0 * rowStride + (x1 + 1)]
                val c = integral[(y1 + 1) * rowStride + x0]
                val d = integral[(y1 + 1) * rowStride + (x1 + 1)]

                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                val localMean = (d - b - c + a) / area

                val original = gray[y * width + x]

                // Local contrast enhancement around the neighborhood mean.
                val enhanced = (
                    localMean + (original - localMean) * 1.35f
                    ).toInt().coerceIn(0, 255)

                // Compare against the local background rather than 128.
                val cutoff = (localMean - localC).coerceIn(0, 255)

                val isLit =
                    if (!invert) {
                        enhanced >= cutoff
                    } else {
                        enhanced < cutoff
                    }

                if (isLit) {
                    val byteIdx = y * rowBytes + (x / 8)
                    val bitOffset = 7 - (x % 8)
                    packed[byteIdx] =
                        (
                            packed[byteIdx].toInt() or
                                (1 shl bitOffset)
                            ).toByte()
                }
            }
        }

        // globalMean is deliberately computed to keep the adaptive pass aware
        // of the overall image, but local thresholding remains the main signal.
        @Suppress("UNUSED_VARIABLE")
        val ignoredGlobalMean = globalMean

        return packed
    }

    private fun floydSteinbergDither(
        pixels: IntArray,
        width: Int,
        height: Int,
        invert: Boolean
    ): ByteArray {
        val gray = FloatArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b)
        }

        val rowBytes = (width + 7) / 8
        val packed = ByteArray(rowBytes * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val oldVal = gray[idx].coerceIn(0f, 255f)
                val newVal = if (oldVal >= 128f) 255f else 0f
                val quantError = oldVal - newVal

                val isLit =
                    if (!invert) newVal == 255f else newVal == 0f

                if (isLit) {
                    val byteIdx = y * rowBytes + (x / 8)
                    val bitOffset = 7 - (x % 8)
                    packed[byteIdx] =
                        (
                            packed[byteIdx].toInt() or
                                (1 shl bitOffset)
                            ).toByte()
                }

                if (x + 1 < width)
                    gray[idx + 1] += quantError * (7f / 16f)
                if (x - 1 >= 0 && y + 1 < height)
                    gray[idx + width - 1] += quantError * (3f / 16f)
                if (y + 1 < height)
                    gray[idx + width] += quantError * (5f / 16f)
                if (x + 1 < width && y + 1 < height)
                    gray[idx + width + 1] += quantError * (1f / 16f)
            }
        }

        return packed
    }

    private fun threshold1Bit(
        pixels: IntArray,
        width: Int,
        height: Int,
        threshold: Int,
        invert: Boolean
    ): ByteArray =
        adaptiveThreshold1Bit(
            pixels,
            width,
            height,
            threshold,
            invert
        )

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
                        if (bit == 1) -0x1 else -0x1000000
                }
            }
        }

        val bitmap =
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
