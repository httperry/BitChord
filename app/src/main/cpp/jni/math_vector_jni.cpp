#include <jni.h>
#include "analyzer/waveform_decimator.h"
#include "analyzer/vocal_mask_matcher.h"
#include "graphics/color_quantizer.h"

using bitchord::smart::WaveformDecimator;
using bitchord::smart::VocalMaskMatcher;
using bitchord::graphics::ColorQuantizer;

extern "C" {

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_smart_NativeMath_computeRmsEnvelope(
    JNIEnv* env, jclass, jfloatArray samples, jint count, jint window_size,
    jfloatArray out_rms, jint out_count) {
  auto* p_samples = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(samples, nullptr));
  auto* p_rms = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(out_rms, nullptr));
  if (p_samples && p_rms) {
    WaveformDecimator::computeRmsEnvelope(p_samples, count, window_size, p_rms, out_count);
  }
  if (p_rms) env->ReleasePrimitiveArrayCritical(out_rms, p_rms, 0);
  if (p_samples) env->ReleasePrimitiveArrayCritical(samples, p_samples, JNI_ABORT);
}

JNIEXPORT jfloat JNICALL
Java_com_music_bitchord_ui_graphics_NativePalette_computeRelativeLuminance(
    JNIEnv* env, jclass, jintArray pixels, jint count) {
  auto* p_pixels = static_cast<jint*>(env->GetPrimitiveArrayCritical(pixels, nullptr));
  float result = 0.0f;
  if (p_pixels) {
    result = ColorQuantizer::computeAverageRelativeLuminance(
        reinterpret_cast<const uint32_t*>(p_pixels), count);
    env->ReleasePrimitiveArrayCritical(pixels, p_pixels, JNI_ABORT);
  }
  return result;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_ui_graphics_NativePalette_buildHistogram(
    JNIEnv* env, jclass, jintArray pixels, jint count, jintArray histogram, jshortArray quantized) {
  auto* p_pixels = static_cast<jint*>(env->GetPrimitiveArrayCritical(pixels, nullptr));
  auto* p_hist = static_cast<jint*>(env->GetPrimitiveArrayCritical(histogram, nullptr));
  auto* p_quant = quantized ? static_cast<jshort*>(env->GetPrimitiveArrayCritical(quantized, nullptr)) : nullptr;

  if (p_pixels && p_hist) {
    ColorQuantizer::buildHistogram(reinterpret_cast<const uint32_t*>(p_pixels), count,
                                  reinterpret_cast<uint32_t*>(p_hist),
                                  reinterpret_cast<uint16_t*>(p_quant));
  }
  if (p_quant) env->ReleasePrimitiveArrayCritical(quantized, p_quant, 0);
  if (p_hist) env->ReleasePrimitiveArrayCritical(histogram, p_hist, 0);
  if (p_pixels) env->ReleasePrimitiveArrayCritical(pixels, p_pixels, JNI_ABORT);
}

}  // extern "C"
