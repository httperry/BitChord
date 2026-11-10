package com.music.bitchord.ui.graphics

/**
 * Native C++ ARM NEON vectorization bridge for color quantization (MMCQ)
 * and WCAG relative luminance calculation.
 */
object NativePalette {

    val isAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary("bitchord_analysis")
        }.isSuccess
    }

    /**
     * Vectorized RGB888 -> RGB555 reduction and 32K-bin histogram creation
     * using ARM NEON vld4_u8 hardware 4-channel deinterleaving.
     */
    @JvmStatic
    external fun buildHistogram(
        pixels: IntArray,
        count: Int,
        histogram: IntArray,
        quantized: ShortArray?
    )

    /**
     * Vectorized WCAG relative luminance across image pixel buffers using NEON FMA.
     */
    @JvmStatic
    external fun computeRelativeLuminance(pixels: IntArray, count: Int): Float
}
