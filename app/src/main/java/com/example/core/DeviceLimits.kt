package com.example.core

/**
 * Hardware limits enforced by the smart glasses firmware.
 * Can be dynamically updated if GET_INFO is supported by the firmware.
 */
object DeviceLimits {
    const val DEFAULT_DEVICE_MAX_W: Int = 320
    const val DEFAULT_DEVICE_MAX_H: Int = 240
    const val DEFAULT_MAX_IMAGE_BYTES: Int = 10240
    const val DEFAULT_MAX_TEXT_BYTES: Int = 100
    const val EDITOR_MAX_W: Int = 640
    const val EDITOR_MAX_H: Int = 480

    // Runtime active limits (replaced if GET_INFO answers)
    var deviceMaxW: Int = DEFAULT_DEVICE_MAX_W
    var deviceMaxH: Int = DEFAULT_DEVICE_MAX_H
    var maxImageBytes: Int = DEFAULT_MAX_IMAGE_BYTES
    var maxTextBytes: Int = DEFAULT_MAX_TEXT_BYTES

    fun updateLimits(maxW: Int, maxH: Int, maxImgBytes: Long, maxTxtBytes: Int) {
        deviceMaxW = maxW
        deviceMaxH = maxH
        maxImageBytes = maxImgBytes.toInt()
        maxTextBytes = maxTxtBytes
    }

    fun resetToDefaults() {
        deviceMaxW = DEFAULT_DEVICE_MAX_W
        deviceMaxH = DEFAULT_DEVICE_MAX_H
        maxImageBytes = DEFAULT_MAX_IMAGE_BYTES
        maxTextBytes = DEFAULT_MAX_TEXT_BYTES
    }
}
