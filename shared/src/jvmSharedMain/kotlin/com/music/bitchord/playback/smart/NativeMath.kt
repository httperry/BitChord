package com.music.bitchord.playback.smart

/**
 * Native C++ ARM NEON vector math bridges for real-time waveform decimation,
 * vocal mask matching, and envelope power calculation.
 */
object NativeMath {

    val isAvailable: Boolean get() = NativeAnalysisLibrary.available

    /**
     * Vectorized RMS envelope calculation using 8-way NEON SIMD FMA unrolling.
     */
    @JvmStatic
    external fun computeRmsEnvelope(
        samples: FloatArray,
        count: Int,
        windowSize: Int,
        outRms: FloatArray,
        outCount: Int
    )
}
