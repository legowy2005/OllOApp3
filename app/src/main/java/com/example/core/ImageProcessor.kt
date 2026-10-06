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
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

    /*
     * Do not decode photos directly to the 640x480 display size.
     * The document pipeline needs several thousand source pixels available before
     * its final high-quality resize.  2048 is a good practical ceiling for Android
     * memory while still being much higher resolution than the glasses output.
     */
    private const val PHOTO_DECODE_MAX_W = 2048
    private const val PHOTO_DECODE_MAX_H = 2048

    fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        reqWidth: Int = PHOTO_DECODE_MAX_W,
        reqHeight: Int = PHOTO_DECODE_MAX_H
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

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        if (
            orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return bitmap
        }

        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
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
        canvas.drawLine(
            cx - eyeRadius * 2.3f,
            cy,
            cx - eyeRadius * 2.7f,
            cy - eyeRadius * 0.4f,
            paint
        )
        canvas.drawLine(
            cx + eyeRadius * 2.3f,
            cy,
            cx + eyeRadius * 2.7f,
            cy - eyeRadius * 0.4f,
            paint
        )

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
     * Document-oriented OpenCV pipeline:
     *
     *   high-resolution photo
     *        -> OpenCV grayscale
     *        -> CLAHE local contrast enhancement
     *        -> mild Gaussian denoise
     *        -> aspect-preserving, high-quality resize to the device canvas
     *        -> Sauvola-style local binarization
     *        -> tiny black-speck cleanup
     *        -> packed 1-bit MSB-left bytes
     *
     * New images are always stored as format 0 (1-bit mono).
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

        val transformed = applyUserTransforms(
            sourceBitmap = sourceBitmap,
            rotationDegrees = rotationDegrees,
            flipHorizontal = flipHorizontal,
            flipVertical = flipVertical
        )

        val cropped = if (
            cropLeftRatio > 0f ||
            cropTopRatio > 0f ||
            cropRightRatio < 1f ||
            cropBottomRatio < 1f
        ) {
            cropBitmap(
                transformed,
                cropLeftRatio,
                cropTopRatio,
                cropRightRatio,
                cropBottomRatio
            )
        } else {
            transformed
        }

        val (deviceW, deviceH) = calculateFitDimensions(
            clampedTargetW,
            clampedTargetH,
            DeviceLimits.deviceMaxW,
            DeviceLimits.deviceMaxH
        )

        val packedBytes = processDocumentWithOpenCv(
            bitmap = cropped,
            targetWidth = deviceW,
            targetHeight = deviceH,
            thresholdControl = threshold,
            invert = invert,
            backgroundIsWhite = backgroundColorArgb != AndroidColor.BLACK
        )

        var checksumSum = 0
        for (b in packedBytes) {
            checksumSum += b.toInt() and 0xFF
        }
        val checksum = checksumSum and 0xFF

        val imageId = Crc32.calculateImageId(
            deviceW,
            deviceH,
            packedBytes
        )

        /*
         * Keep the Android-side preview as an ordinary aspect-preserved bitmap.
         * It is not what gets sent to the glasses; deviceData is the authoritative
         * packed 1-bit representation.
         */
        val displayBitmap = fitBitmapToCanvas(
            source = cropped,
            canvasWidth = clampedTargetW,
            canvasHeight = clampedTargetH,
            backgroundColorArgb = backgroundColorArgb
        )

        return ProcessedImageResult(
            imageId = imageId,
            deviceWidth = deviceW,
            deviceHeight = deviceH,
            deviceData = packedBytes,
            displayWidth = displayBitmap.width,
            displayHeight = displayBitmap.height,
            displayData = bitmapToPng(displayBitmap),
            checksum = checksum,
            isColor = false
        )
    }

    private fun applyUserTransforms(
        sourceBitmap: Bitmap,
        rotationDegrees: Float,
        flipHorizontal: Boolean,
        flipVertical: Boolean
    ): Bitmap {
        if (
            rotationDegrees == 0f &&
            !flipHorizontal &&
            !flipVertical
        ) {
            return sourceBitmap
        }

        val matrix = Matrix()

        if (rotationDegrees != 0f) {
            matrix.postRotate(rotationDegrees)
        }

        if (flipHorizontal || flipVertical) {
            val sx = if (flipHorizontal) -1f else 1f
            val sy = if (flipVertical) -1f else 1f
            matrix.postScale(sx, sy)
        }

        return Bitmap.createBitmap(
            sourceBitmap,
            0,
            0,
            sourceBitmap.width,
            sourceBitmap.height,
            matrix,
            true
        )
    }

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

    /**
     * Full document pipeline. The important difference from the earlier OpenCV
     * implementation is that the photo is NOT first shrunk to 640x480. OpenCV gets
     * the high-resolution decoded bitmap and performs the final resize only after
     * contrast enhancement and denoising.
     */
    private fun processDocumentWithOpenCv(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        thresholdControl: Int,
        invert: Boolean,
        backgroundIsWhite: Boolean
    ): ByteArray {
        val srcRgba = Mat()
        val gray = Mat()
        val enhanced = Mat()
        val denoised = Mat()
        val resized = Mat()
        val binary = Mat()
        val cleaned = Mat()
        val clahe = Imgproc.createCLAHE(1.8, Size(8.0, 8.0))

        try {
            Utils.bitmapToMat(bitmap, srcRgba, true)

            // 1. High-resolution RGB/RGBA -> grayscale.
            Imgproc.cvtColor(srcRgba, gray, Imgproc.COLOR_RGBA2GRAY)

            // 2. Local contrast enhancement (excellent for shadows and uneven lighting).
            clahe.apply(gray, enhanced)

            // 3. Noise reduction before the final resize.
            // A 5x5 median is much better at suppressing camera/screen speckle
            // than a very small Gaussian while keeping document edges crisp.
            Imgproc.medianBlur(enhanced, denoised, 5)

            // 4. High-quality, aspect-preserving resize onto a white/black canvas.
            fitGrayToCanvas(
                source = denoised,
                target = resized,
                canvasWidth = targetWidth,
                canvasHeight = targetHeight,
                backgroundIsWhite = backgroundIsWhite
            )

            // 5. Sauvola-style adaptive binarization.
            sauvolaThreshold(
                gray = resized,
                binary = binary,
                thresholdControl = thresholdControl,
                invert = invert
            )

            // 6. Remove tiny black specks without aggressively altering characters.
            removeBlackSpecks(binary, cleaned)

            // 7. Pack directly into OllO's 1-bit MSB-left format.
            return packBinaryMat(cleaned)
        } finally {
            clahe.collectGarbage()
            cleaned.release()
            binary.release()
            resized.release()
            denoised.release()
            enhanced.release()
            gray.release()
            srcRgba.release()
        }
    }

    /**
     * Resize while preserving aspect ratio and placing the result on the display
     * canvas. This happens AFTER CLAHE and noise reduction so the final resize has
     * the best source data available.
     */
    private fun fitGrayToCanvas(
        source: Mat,
        target: Mat,
        canvasWidth: Int,
        canvasHeight: Int,
        backgroundIsWhite: Boolean
    ) {
        target.create(canvasHeight, canvasWidth, CvType.CV_8UC1)
        target.setTo(
            Scalar.all(if (backgroundIsWhite) 255.0 else 0.0)
        )

        val scale = min(
            canvasWidth.toDouble() / source.cols().toDouble(),
            canvasHeight.toDouble() / source.rows().toDouble()
        )

        val scaledW = max(1, (source.cols() * scale).toInt())
        val scaledH = max(1, (source.rows() * scale).toInt())

        val scaled = Mat()
        try {
            Imgproc.resize(
                source,
                scaled,
                Size(scaledW.toDouble(), scaledH.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_LANCZOS4
            )

            val x0 = (canvasWidth - scaledW) / 2
            val y0 = (canvasHeight - scaledH) / 2

            scaled.copyTo(
                target.submat(
                    y0,
                    y0 + scaledH,
                    x0,
                    x0 + scaledW
                )
            )
        } finally {
            scaled.release()
        }
    }

    /**
     * Sauvola threshold using OpenCV box filtering for the local mean/variance.
     *
     * Formula:
     *   T = m * (1 + k * (s / R - 1))
     *
     * gray is normalized to 0..1, so R=0.5 (the maximum meaningful standard
     * deviation in that normalized range). The existing threshold slider gently
     * adjusts k around the default ~0.33 instead of becoming a global cutoff.
     */
    private fun sauvolaThreshold(
        gray: Mat,
        binary: Mat,
        thresholdControl: Int,
        invert: Boolean
    ) {
        val normalized = Mat()
        val squared = Mat()
        val mean = Mat()
        val meanSquared = Mat()

        try {
            gray.convertTo(normalized, CvType.CV_32FC1, 1.0 / 255.0)
            Core.multiply(normalized, normalized, squared)

            val window = 51
            val kernel = Size(window.toDouble(), window.toDouble())

            Imgproc.blur(normalized, mean, kernel)
            Imgproc.blur(squared, meanSquared, kernel)

            val count = gray.rows() * gray.cols()
            val meanValues = FloatArray(count)
            val meanSquaredValues = FloatArray(count)
            mean.get(0, 0, meanValues)
            meanSquared.get(0, 0, meanSquaredValues)

            val k = 0.12 + (thresholdControl.coerceIn(0, 255) / 255.0) * 0.24
            val r = 0.5
            val pixels = ByteArray(count)
            gray.get(0, 0, pixels)
            val output = ByteArray(count)

            for (i in 0 until count) {
                val m = meanValues[i].toDouble()
                val variance = max(
                    0.0,
                    meanSquaredValues[i].toDouble() - m * m
                )
                val stdDev = sqrt(variance)
                val localThreshold = m * (1.0 + k * (stdDev / r - 1.0))
                val pixel = pixels[i].toInt() and 0xFF

                var white = pixel >= localThreshold * 255.0
                if (invert) white = !white

                output[i] = if (white) 255.toByte() else 0.toByte()
            }

            binary.create(gray.rows(), gray.cols(), CvType.CV_8UC1)
            binary.put(0, 0, output)
        } finally {
            meanSquared.release()
            mean.release()
            squared.release()
            normalized.release()
        }
    }

    /**
     * Treat the black pixels as the foreground, open them with a tiny 2x2 kernel,
     * then invert back. This specifically targets isolated dark specks—the problem
     * the previous MORPH_OPEN on the normal white-background image did not address.
     */
    private fun removeBlackSpecks(
        binary: Mat,
        cleaned: Mat
    ) {
        val inverted = Mat()
        val kernel = Mat.ones(2, 2, CvType.CV_8UC1)

        try {
            Core.bitwise_not(binary, inverted)
            Imgproc.morphologyEx(
                inverted,
                inverted,
                Imgproc.MORPH_OPEN,
                kernel
            )
            Core.bitwise_not(inverted, cleaned)
        } finally {
            kernel.release()
            inverted.release()
        }
    }

    private fun packBinaryMat(binary: Mat): ByteArray {
        val width = binary.cols()
        val height = binary.rows()
        val rowBytes = (width + 7) / 8
        val packed = ByteArray(rowBytes * height)
        val row = ByteArray(width)

        for (y in 0 until height) {
            binary.get(y, 0, row)
            for (x in 0 until width) {
                if ((row[x].toInt() and 0xFF) != 0) {
                    val byteIndex = y * rowBytes + (x / 8)
                    val bitOffset = 7 - (x % 8)
                    packed[byteIndex] =
                        (packed[byteIndex].toInt() or (1 shl bitOffset)).toByte()
                }
            }
        }

        return packed
    }

    private fun bitmapToPng(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(
            Bitmap.CompressFormat.PNG,
            100,
            output
        )
        return output.toByteArray()
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

        val fitW = (w * scale).toInt().coerceIn(1, maxW)
        val fitH = (h * scale).toInt().coerceIn(1, maxH)

        return Pair(fitW, fitH)
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
