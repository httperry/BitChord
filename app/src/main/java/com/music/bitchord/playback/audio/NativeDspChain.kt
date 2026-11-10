package com.music.bitchord.playback.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Zero-copy ARM NEON-accelerated native audio DSP engine for BitChord.
 * Replaces pure Kotlin DSP math with SIMD vectorized C++ kernels.
 */
class NativeDspChain(sampleRate: Int, channelCount: Int) : AutoCloseable {

    private var nativeHandle: Long = 0L

    val isAvailable: Boolean get() = nativeHandle != 0L

    init {
        nativeHandle = runCatching {
            System.loadLibrary("bitchord_dsp")
            nativeCreate(sampleRate, channelCount)
        }.getOrDefault(0L)
    }

    fun configure(sampleRate: Int, channelCount: Int) {
        if (nativeHandle != 0L) {
            nativeConfigure(nativeHandle, sampleRate, channelCount)
        }
    }

    fun setSpatializer(enabled: Boolean) {
        if (nativeHandle != 0L) {
            nativeSetSpatializer(nativeHandle, enabled)
        }
    }

    fun setEqualizer(enabled: Boolean, gainsDb: FloatArray?, qs: FloatArray?, preampDb: Float, balance: Float) {
        if (nativeHandle != 0L) {
            nativeSetEqualizer(nativeHandle, enabled, gainsDb, qs, preampDb, balance)
        }
    }

    fun setDualEqualizer(
        enabled: Boolean,
        gainsDbL: FloatArray?,
        qsL: FloatArray?,
        gainsDbR: FloatArray?,
        qsR: FloatArray?,
        preampDb: Float,
        balance: Float,
    ) {
        if (nativeHandle != 0L) {
            nativeSetDualEqualizer(nativeHandle, enabled, gainsDbL, qsL, gainsDbR, qsR, preampDb, balance)
        }
    }

    fun setBassSwapShelf(enabled: Boolean, attenuationDb: Float = -24.0f, cutoffHz: Float = 150.0f) {
        if (nativeHandle != 0L) {
            nativeSetBassSwapShelf(nativeHandle, enabled, attenuationDb, cutoffHz)
        }
    }

    fun setLimiter(targetGain: Float, targetTrim: Float, holdLimiter: Boolean, snap: Boolean = false) {
        if (nativeHandle != 0L) {
            nativeSetLimiter(nativeHandle, targetGain, targetTrim, holdLimiter, snap)
        }
    }

    fun flush() {
        if (nativeHandle != 0L) {
            nativeFlush(nativeHandle)
        }
    }

    fun reset() {
        if (nativeHandle != 0L) {
            nativeReset(nativeHandle)
        }
    }

    /**
     * In-place zero-copy DSP execution on [directBuffer].
     */
    fun process(directBuffer: ByteBuffer, frameCount: Int, channelCount: Int) {
        require(directBuffer.isDirect) { "Audio buffer must be allocateDirect for zero-copy JNI" }
        if (nativeHandle != 0L && frameCount > 0) {
            nativeProcess(nativeHandle, directBuffer, frameCount, channelCount)
        }
    }

    /**
     * In-place DSP execution on [samples] FloatArray using critical memory pinning.
     */
    fun process(samples: FloatArray, frameCount: Int, channelCount: Int) {
        if (nativeHandle != 0L && frameCount > 0) {
            nativeProcessFloatArray(nativeHandle, samples, frameCount, channelCount)
        }
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    companion object {
        @JvmStatic private external fun nativeCreate(sampleRate: Int, channelCount: Int): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeConfigure(handle: Long, sampleRate: Int, channelCount: Int)
        @JvmStatic private external fun nativeSetSpatializer(handle: Long, enabled: Boolean)
        @JvmStatic private external fun nativeSetEqualizer(
            handle: Long, enabled: Boolean, gainsDb: FloatArray?, qs: FloatArray?, preampDb: Float, balance: Float
        )
        @JvmStatic private external fun nativeSetDualEqualizer(
            handle: Long, enabled: Boolean,
            gainsDbL: FloatArray?, qsL: FloatArray?,
            gainsDbR: FloatArray?, qsR: FloatArray?,
            preampDb: Float, balance: Float
        )
        @JvmStatic private external fun nativeSetBassSwapShelf(
            handle: Long, enabled: Boolean, attenuationDb: Float, cutoffHz: Float
        )
        @JvmStatic private external fun nativeSetLimiter(
            handle: Long, targetGain: Float, targetTrim: Float, holdLimiter: Boolean, snap: Boolean
        )
        @JvmStatic private external fun nativeFlush(handle: Long)
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeProcess(
            handle: Long, directBuffer: ByteBuffer, frameCount: Int, channelCount: Int
        )
        @JvmStatic private external fun nativeProcessFloatArray(
            handle: Long, samples: FloatArray, frameCount: Int, channelCount: Int
        )
    }
}