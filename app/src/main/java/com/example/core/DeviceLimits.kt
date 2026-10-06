package com.example.core

/**
 * Hardware limits enforced by the smart glasses firmware.
 * Updated at runtime from the glasses' GET_INFO reply.
 */
object DeviceLimits {
    const val DEFAULT_DEVICE_MAX_W: Int = 640
    const val DEFAULT_DEVICE_MAX_H: Int = 480
    const val DEFAULT_MAX_IMAGE_BYTES: Int = 153600
    const val DEFAULT_MAX_TEXT_BYTES: Int = 100
    const val EDITOR_MIN_W: Int = 320
    const val EDITOR_MIN_H: Int = 240
    const val EDITOR_MAX_W: Int = 640
    const val EDITOR_MAX_H: Int = 480

    // RGB565 is intentionally limited to 320x240 to keep per-image storage bounded.
    const val COLOR_MAX_W: Int = 320
    const val COLOR_MAX_H: Int = 240
    const val COLOR_MAX_IMAGE_BYTES: Int = COLOR_MAX_W * COLOR_MAX_H * 2

    var deviceMaxW: Int = DEFAULT_DEVICE_MAX_W
    var deviceMaxH: Int = DEFAULT_DEVICE_MAX_H
    var maxImageBytes: Int = DEFAULT_MAX_IMAGE_BYTES
    var maxTextBytes: Int = DEFAULT_MAX_TEXT_BYTES
    var supportsColor: Boolean = false
    var supportsGray2: Boolean = false

    /** Image formats: 0 = 1-bit, 1 = RGB565 color, 2 = 2-bit gray. */
    fun supportsImageFormat(format: Int): Boolean = when (format) {
        0 -> true
        1 -> supportsColor
        2 -> supportsGray2
        else -> false
    }

    fun updateLimits(
        maxW: Int,
        maxH: Int,
        maxImgBytes: Long,
        maxTxtBytes: Int,
        colorSupported: Boolean = false,
        gray2Supported: Boolean = false
    ) {
        deviceMaxW = maxW
        deviceMaxH = maxH
        maxImageBytes = maxImgBytes.toInt()
        maxTextBytes = maxTxtBytes
        supportsColor = colorSupported
        supportsGray2 = gray2Supported
    }

    fun resetToDefaults() {
        deviceMaxW = DEFAULT_DEVICE_MAX_W
        deviceMaxH = DEFAULT_DEVICE_MAX_H
        maxImageBytes = DEFAULT_MAX_IMAGE_BYTES
        maxTextBytes = DEFAULT_MAX_TEXT_BYTES
        supportsColor = false
        supportsGray2 = false
    }
}
