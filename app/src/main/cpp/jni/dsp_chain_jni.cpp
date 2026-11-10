#include <jni.h>
#include <new>
#include "neon_biquad_eq.h"
#include "neon_spatializer.h"
#include "neon_limiter.h"

using bitchord::dsp::NeonBiquadEq;
using bitchord::dsp::NeonSpatializer;
using bitchord::dsp::NeonLimiter;

namespace {

struct NativeDspContext {
  NeonSpatializer spatializer;
  NeonBiquadEq equalizer;
  NeonLimiter limiter;

  void Configure(int sample_rate, int channel_count) {
    spatializer.Configure(sample_rate, channel_count);
    equalizer.Configure(sample_rate, channel_count);
    limiter.Configure(sample_rate, channel_count);
  }

  void Reset() {
    spatializer.Reset();
    equalizer.Reset();
    limiter.Reset();
  }

  void Flush() {
    spatializer.Flush();
    equalizer.Flush();
    limiter.Reset();
  }
};

inline NativeDspContext* FromHandle(jlong handle) {
  return reinterpret_cast<NativeDspContext*>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_audio_NativeDspChain_nativeCreate(
    JNIEnv*, jclass, jint sampleRate, jint channelCount) {
  auto* ctx = new (std::nothrow) NativeDspContext();
  if (ctx != nullptr) {
    ctx->Configure(sampleRate, channelCount);
  }
  return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_NativeDspChain_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
  delete FromHandle(handle);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_NativeDspChain_nativeConfigure(
    JNIEnv*, jclass, jlong handle, jint sampleRate, jint channelCount) {
  auto* ctx = FromHandle(handle);
  if (ctx != nullptr) {
    ctx->Configure(sampleRate, channelCount);
  }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_NativeDspChain_nativeSetSpatializer(
    JNIEnv*, jclass, jlong handle, jboolean enabled) {
  auto* ctx = FromHandle(handle);
  if (ctx != nullptr) {
    ctx->spatializer.SetEnabled(enabled);
  }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_NativeDspChain_nativeSetEqualizer(
    JNIEnv* env, jclass, jlong handle, jboolean enabled, jfloatArray gainsDb, jfloatArray qs,
    jfloat preampDb, jfloat balance) {
  auto* ctx = FromHandle(handle);
  if (ctx == nullptr) return;

  if (!enabled || gainsDb == nullptr || qs == nullptr) {
    ctx->equalizer.SetTuning(false, nullptr, nullptr, 0.0f, 0.0f);
    return;
  }

  float g_buf[10];
  float q_buf[10];
  env->GetFloatArrayRegion(gainsDb, 0, 10, g_buf);
  env->GetFloatArrayRegion(qs, 0, 10, q_buf);
  ctx->equalizer.SetTuning(true, g_buf, q_buf, preampDb, balance);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_NativeDspChain_nativeSetDualEqualizer(
    JNIEnv* env, jclass, jlong handle, jboolean enabled,
    jfloatArray gainsDbL, jfloatArray qsL,
    jfloatArray gainsDbR, jfloatArray qsR,
    jfloat preampDb, jfloat balance) {
  auto* ctx = FromHandle(handle);
  if (ctx == nullptr) return;

  if (!enabled || gainsDbL == nullptr || qsL == nullptr || gainsDbR == nullptr || qsR == nullptr) {
    ctx->equalizer.SetDualTuning(false, nullptr, nullptr, nullptr, nullptr, 0.0f, 0.0f);
    return;
  }

  float g_buf_l[10];
  float q_buf_l[10];
  float g_buf_r[10];
  float q_buf_r[10];
  env->GetFloatArrayRegion(gainsDbL, 0, 10, g_buf_l);
  env->GetFloatArrayRegion(qsL, 0, 10, q_buf_l);
  env->GetFloatArrayRegion(gainsDbR, 0, 10, g_buf_r);
  env->GetFloatArrayRegion(qsR, 0, 10, q_buf_r);
  ctx->equalizer.SetDualTuning(true, g_buf_l, q_buf_l, g_buf_r, q_buf_r, preampDb, balance);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_NativeDspChain_nativeSetBassSwapShelf(
    JNIEnv*, jclass, jlong handle, jboolean enabled, jfloat attenuationDb, jfloat cutoffHz) {
  auto* ctx = FromHandle(handle);
  if (ctx != nullptr) {
    ctx->equalizer.SetBassSwapShelf(enabled, attenuationDb, cutoffHz);
  }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_NativeDspChain_nativeSetLimiter(
    JNIEnv*, jclass, jlong handle, jfloat targetGain, jfloat targetTrim, jboolean holdLimiter, jboolean snap) {
  auto* ctx = FromHandle(handle);
  if (ctx != nullptr) {
    ctx->limiter.SetParameters(targetGain, targetTrim, holdLimiter, snap);
  }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_NativeDspChain_nativeFlush(
    JNIEnv*, jclass, jlong handle) {
  auto* ctx = FromHandle(handle);
  if (ctx != nullptr) {
    ctx->Flush();
  }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_NativeDspChain_nativeReset(
    JNIEnv*, jclass, jlong handle) {
  auto* ctx = FromHandle(handle);
  if (ctx != nullptr) {
    ctx->Reset();
  }
}

/**
 * CRITICAL REAL-TIME ZERO-COPY DSP AUDIO EXECUTION
 * Uses GetDirectBufferAddress to operate 100% in-place without GC pinning,
 * memory allocations, or buffer copies.
 */
JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_NativeDspChain_nativeProcess(
    JNIEnv* env, jclass, jlong handle, jobject directByteBuffer, jint frameCount, jint channelCount) {
  auto* ctx = FromHandle(handle);
  if (ctx == nullptr || frameCount <= 0 || directByteBuffer == nullptr) return;

  // Zero-copy direct memory resolution (1-2 ns inline pointer fetch)
  auto* audio_ptr = static_cast<float*>(env->GetDirectBufferAddress(directByteBuffer));
  if (audio_ptr == nullptr) return;

  // Execute native ARM NEON DSP chain in canonical sequence
  ctx->spatializer.Process(audio_ptr, frameCount);
  ctx->equalizer.Process(audio_ptr, frameCount);

  // Equalizer informs limiter whether shaping is actively producing overs
  const bool hold_limiter = ctx->equalizer.IsShaping();
  ctx->limiter.SetParameters(1.0f, 1.0f, hold_limiter, false);
  ctx->limiter.Process(audio_ptr, frameCount);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_NativeDspChain_nativeProcessFloatArray(
    JNIEnv* env, jclass, jlong handle, jfloatArray floatArray, jint frameCount, jint channelCount) {
  auto* ctx = FromHandle(handle);
  if (ctx == nullptr || frameCount <= 0 || floatArray == nullptr) return;

  auto* audio_ptr = static_cast<float*>(env->GetPrimitiveArrayCritical(floatArray, nullptr));
  if (audio_ptr == nullptr) return;

  ctx->spatializer.Process(audio_ptr, frameCount);
  ctx->equalizer.Process(audio_ptr, frameCount);

  const bool hold_limiter = ctx->equalizer.IsShaping();
  ctx->limiter.SetParameters(1.0f, 1.0f, hold_limiter, false);
  ctx->limiter.Process(audio_ptr, frameCount);

  env->ReleasePrimitiveArrayCritical(floatArray, audio_ptr, 0);
}

}  // extern "C"