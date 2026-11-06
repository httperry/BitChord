// JNI bridge for the Automix loop mix renderer (native/loopmix/). Every call
// but render() is made while the mix is being set up; render() runs on the
// player's playback thread, inside its audio sink.
//
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <new>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

#include "loopmix/loop_mix_renderer.h"

using bitchord::loopmix::Deck;
using bitchord::loopmix::Envelope;
using bitchord::loopmix::Renderer;

namespace {

Renderer* From(jlong handle) { return reinterpret_cast<Renderer*>(handle); }

Deck* DeckOf(jlong handle, jint index) {
  if (handle == 0 || index < 0 || index > 1) return nullptr;
  return &From(handle)->deck(index);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeCreate(JNIEnv*, jclass, jint sampleRate) {
  return reinterpret_cast<jlong>(new (std::nothrow) Renderer(sampleRate));
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeDestroy(JNIEnv*, jclass, jlong handle) {
  delete From(handle);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetDeck(
    JNIEnv* env, jclass, jlong handle, jint index, jfloatArray left, jfloatArray right,
    jdouble regionStart, jlong loopStart, jlong loopEnd, jdouble releaseAt,
    jdoubleArray knotFrames, jdoubleArray knotPositions, jboolean runOn, jdouble stopAt,
    jdouble preroll, jfloat level) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  const jsize frames = std::min(env->GetArrayLength(left), env->GetArrayLength(right));
  deck->left.resize(frames);
  deck->right.resize(frames);
  env->GetFloatArrayRegion(left, 0, frames, deck->left.data());
  env->GetFloatArrayRegion(right, 0, frames, deck->right.data());
  deck->regionStart = regionStart;
  deck->loopStart = loopStart;
  deck->loopEnd = loopEnd;
  deck->releaseAt = releaseAt;
  const jsize knots = std::min(env->GetArrayLength(knotFrames), env->GetArrayLength(knotPositions));
  deck->knotTimes.resize(knots);
  deck->knotPositions.resize(knots);
  env->GetDoubleArrayRegion(knotFrames, 0, knots, deck->knotTimes.data());
  env->GetDoubleArrayRegion(knotPositions, 0, knots, deck->knotPositions.data());
  deck->runOn = runOn;
  deck->stopAt = stopAt;
  deck->preroll = preroll;
  deck->level = level;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetEnvelope(
    JNIEnv* env, jclass, jlong handle, jint index, jint kind, jdoubleArray times, jfloatArray values,
    jboolean exponential) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  Envelope* envelope = kind == 0 ? &deck->gain : kind == 1 ? &deck->lowDb : kind == 2 ? &deck->highPassHz : nullptr;
  if (envelope == nullptr) return;
  const jsize points = std::min(env->GetArrayLength(times), env->GetArrayLength(values));
  envelope->times.resize(points);
  envelope->values.resize(points);
  env->GetDoubleArrayRegion(times, 0, points, envelope->times.data());
  env->GetFloatArrayRegion(values, 0, points, envelope->values.data());
  envelope->exponential = exponential;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativePrepare(JNIEnv*, jclass, jlong handle) {
  if (handle != 0) From(handle)->prepare();
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeFirstFrame(JNIEnv*, jclass, jlong handle) {
  return handle != 0 ? From(handle)->firstFrame() : 0;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeRender(
    JNIEnv* env, jclass, jlong handle, jlong frame, jfloatArray interleaved, jint offsetFrames, jint frames) {
  if (handle == 0 || frames <= 0) return;
  // Critical access: no copy of the block, and render() itself never calls back into Java.
  auto* samples = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(interleaved, nullptr));
  if (samples == nullptr) return;
  From(handle)->render(frame, samples + 2 * offsetFrames, frames);
  env->ReleasePrimitiveArrayCritical(interleaved, samples, 0);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetFadeOut(JNIEnv*, jclass, jlong handle, jint index,
                                                                        jdouble trackSeconds) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  deck->fadeOutAt.store(trackSeconds < 0 ? -1.0
                                         : deck->releasedPositionOf(trackSeconds, From(handle)->sampleRate()));
}

JNIEXPORT jdouble JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeHeard(JNIEnv*, jclass, jlong handle, jint index) {
  Deck* deck = DeckOf(handle, index);
  return deck != nullptr ? deck->heard.load() : 0.0;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSpliceLag(
    JNIEnv* env, jclass, jfloatArray songArray, jfloatArray deckArray, jint length, jint maxLag) {
  if (songArray == nullptr || deckArray == nullptr || length <= 0 || maxLag <= 0) return nullptr;

  auto* song = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(songArray, nullptr));
  if (song == nullptr) return nullptr;
  auto* deck = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(deckArray, nullptr));
  if (deck == nullptr) {
    env->ReleasePrimitiveArrayCritical(songArray, song, JNI_ABORT);
    return nullptr;
  }

  int bestLag = 0;
  double bestScore = -1e18;
  double bestNorm = 0.0;

  for (int lag = -maxLag; lag <= maxLag; ++lag) {
    const int from = std::max(0, -lag);
    const int to = std::min((int)length, (int)(length - lag));
    const int count = to - from;
    if (count <= 0) continue;

    const float* s = song + from;
    const float* d = deck + from + lag;

    double dot = 0.0;
    double sSum = 0.0;
    double dSum = 0.0;

#if defined(__ARM_NEON) || defined(__aarch64__)
    float32x4_t vDot = vdupq_n_f32(0.0f);
    float32x4_t vSong = vdupq_n_f32(0.0f);
    float32x4_t vDeck = vdupq_n_f32(0.0f);

    int i = 0;
    for (; i + 4 <= count; i += 4) {
      float32x4_t a = vld1q_f32(s + i);
      float32x4_t b = vld1q_f32(d + i);
      vDot = vmlaq_f32(vDot, a, b);
      vSong = vmlaq_f32(vSong, a, a);
      vDeck = vmlaq_f32(vDeck, b, b);
    }

    float fDot[4], fSong[4], fDeck[4];
    vst1q_f32(fDot, vDot);
    vst1q_f32(fSong, vSong);
    vst1q_f32(fDeck, vDeck);

    dot = (double)fDot[0] + fDot[1] + fDot[2] + fDot[3];
    sSum = (double)fSong[0] + fSong[1] + fSong[2] + fSong[3];
    dSum = (double)fDeck[0] + fDeck[1] + fDeck[2] + fDeck[3];

    for (; i < count; ++i) {
      const double a = s[i];
      const double b = d[i];
      dot += a * b;
      sSum += a * a;
      dSum += b * b;
    }
#else
    for (int i = 0; i < count; ++i) {
      const double a = s[i];
      const double b = d[i];
      dot += a * b;
      sSum += a * a;
      dSum += b * b;
    }
#endif

    const double norm = std::sqrt(sSum * dSum);
    if (norm > 0.0 && (dot / norm) > bestScore) {
      bestScore = dot / norm;
      bestLag = lag;
      bestNorm = norm;
    }
  }

  env->ReleasePrimitiveArrayCritical(deckArray, deck, JNI_ABORT);
  env->ReleasePrimitiveArrayCritical(songArray, song, JNI_ABORT);

  if (bestNorm <= 0.0) return nullptr;

  jdoubleArray result = env->NewDoubleArray(2);
  if (result == nullptr) return nullptr;
  jdouble out[2] = {static_cast<double>(bestLag), bestScore};
  env->SetDoubleArrayRegion(result, 0, 2, out);
  return result;
}

}  // extern "C"
