#include "waveform_decimator.h"

#include <algorithm>
#include <cmath>
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace bitchord::smart {

namespace {
constexpr double kPi = 3.14159265358979323846;
}

WaveformDecimator::WaveformDecimator(int bins, double low_hz, double high_hz)
    : bins_(bins), low_hz_(low_hz), high_hz_(high_hz) {
  if (bins_ > 0) {
    low_peaks_.assign(bins_, 0.0f);
    mid_peaks_.assign(bins_, 0.0f);
    high_peaks_.assign(bins_, 0.0f);
  }
}

void WaveformDecimator::processBlock(const float* samples, size_t count,
                                     double at_seconds, double from_seconds,
                                     double to_seconds, int sample_rate) {
  if (count == 0 || sample_rate <= 0 || bins_ <= 0 || to_seconds <= from_seconds) return;

  if (sample_rate != configured_rate_) {
    configured_rate_ = sample_rate;
    low_a_ = static_cast<float>(1.0 - std::exp(-2.0 * kPi * low_hz_ / sample_rate));
    high_a_ = static_cast<float>(1.0 - std::exp(-2.0 * kPi * high_hz_ / sample_rate));
  }

  const double span = to_seconds - from_seconds;
  const double bins_per_second = bins_ / span;
  const double bin_step = bins_per_second / sample_rate;
  const double first_bin = (at_seconds - from_seconds) * bins_per_second;

  float l1 = l1_, l2 = l2_, h1 = h1_, h2 = h2_;
  const float low_a = low_a_, high_a = high_a_;

  float* p_low = low_peaks_.data();
  float* p_mid = mid_peaks_.data();
  float* p_high = high_peaks_.data();
  const int total_bins = bins_;

  for (size_t index = 0; index < count; ++index) {
    const float x = samples[index];

    // Cascaded 1-pole IIR updates (in registers, zero memory overhead)
    l1 += low_a * (x - l1);
    l2 += low_a * (l1 - l2);
    h1 += high_a * (x - h1);
    h2 += high_a * (h1 - h2);

    const double position = first_bin + static_cast<double>(index) * bin_step;
    if (position < 0.0) continue;
    const int bin = static_cast<int>(position);
    if (bin >= total_bins) break;

    const float lo = std::abs(l2);
    const float mi = std::abs(h2 - l2);
    const float hi = std::abs(x - h2);

    if (lo > p_low[bin]) p_low[bin] = lo;
    if (mi > p_mid[bin]) p_mid[bin] = mi;
    if (hi > p_high[bin]) p_high[bin] = hi;
  }

  l1_ = l1; l2_ = l2; h1_ = h1; h2_ = h2;
}

// Vectorized RMS Envelope Calculation (8-way NEON unrolled)
void WaveformDecimator::computeRmsEnvelope(const float* samples, size_t count,
                                           size_t window_size, float* out_rms, size_t out_count) {
  if (window_size == 0 || out_count == 0) return;

  for (size_t w = 0; w < out_count; ++w) {
    const size_t start = w * window_size;
    if (start >= count) {
      out_rms[w] = 0.0f;
      continue;
    }
    const size_t end = std::min(count, start + window_size);
    const size_t n = end - start;

    size_t i = start;
#if defined(__ARM_NEON) || defined(__aarch64__)
    float32x4_t sum0 = vdupq_n_f32(0.0f);
    float32x4_t sum1 = vdupq_n_f32(0.0f);

    for (; i + 8 <= end; i += 8) {
      float32x4_t v0 = vld1q_f32(samples + i);
      float32x4_t v1 = vld1q_f32(samples + i + 4);
      sum0 = vfmaq_f32(sum0, v0, v0);
      sum1 = vfmaq_f32(sum1, v1, v1);
    }
    float total_sq = vaddvq_f32(vaddq_f32(sum0, sum1));
#else
    float total_sq = 0.0f;
#endif
    for (; i < end; ++i) {
      total_sq += samples[i] * samples[i];
    }
    out_rms[w] = std::sqrt(total_sq / static_cast<float>(n));
  }
}

// Vectorized Min/Max Waveform Decimation (8-way NEON unrolled)
void WaveformDecimator::computeMinMaxDecimation(const float* samples, size_t count,
                                               float* out_min, float* out_max, size_t bins) {
  if (bins == 0 || count == 0) return;
  const double samples_per_bin = static_cast<double>(count) / bins;

  for (size_t b = 0; b < bins; ++b) {
    const size_t start = static_cast<size_t>(b * samples_per_bin);
    const size_t end = std::min(count, static_cast<size_t>((b + 1) * samples_per_bin));
    if (start >= end) {
      out_min[b] = 0.0f;
      out_max[b] = 0.0f;
      continue;
    }

    size_t i = start;
#if defined(__ARM_NEON) || defined(__aarch64__)
    float32x4_t min_vec0 = vdupq_n_f32(1e30f);
    float32x4_t min_vec1 = vdupq_n_f32(1e30f);
    float32x4_t max_vec0 = vdupq_n_f32(-1e30f);
    float32x4_t max_vec1 = vdupq_n_f32(-1e30f);

    for (; i + 8 <= end; i += 8) {
      float32x4_t v0 = vld1q_f32(samples + i);
      float32x4_t v1 = vld1q_f32(samples + i + 4);
      min_vec0 = vminq_f32(min_vec0, v0);
      min_vec1 = vminq_f32(min_vec1, v1);
      max_vec0 = vmaxq_f32(max_vec0, v0);
      max_vec1 = vmaxq_f32(max_vec1, v1);
    }
    float b_min = vminvq_f32(vminq_f32(min_vec0, min_vec1));
    float b_max = vmaxvq_f32(vmaxq_f32(max_vec0, max_vec1));
#else
    float b_min = 1e30f;
    float b_max = -1e30f;
#endif
    for (; i < end; ++i) {
      b_min = std::min(b_min, samples[i]);
      b_max = std::max(b_max, samples[i]);
    }
    out_min[b] = b_min;
    out_max[b] = b_max;
  }
}

// Percentile Normalization with O(N) std::nth_element and NEON scaling
void WaveformDecimator::normalizeBand(std::vector<float>& band, float ceiling) {
  if (band.empty()) return;

  std::vector<float> non_zero;
  non_zero.reserve(band.size());
  for (float v : band) {
    if (v > 0.0f) non_zero.push_back(v);
  }
  if (non_zero.empty()) return;

  const size_t rank = std::min(non_zero.size() - 1, static_cast<size_t>(non_zero.size() * 0.97));
  std::nth_element(non_zero.begin(), non_zero.begin() + rank, non_zero.end());
  const float reference = non_zero[rank];
  if (reference <= 1e-6f) return;

  const float scale = ceiling / reference;
  const float ceiling_limit = ceiling * 1.08f;

  float* data = band.data();
  const size_t size = band.size();
  size_t i = 0;

#if defined(__ARM_NEON) || defined(__aarch64__)
  const float32x4_t v_scale = vdupq_n_f32(scale);
  const float32x4_t v_limit = vdupq_n_f32(ceiling_limit);

  for (; i + 8 <= size; i += 8) {
    float32x4_t d0 = vld1q_f32(data + i);
    float32x4_t d1 = vld1q_f32(data + i + 4);
    d0 = vminq_f32(vmulq_f32(d0, v_scale), v_limit);
    d1 = vminq_f32(vmulq_f32(d1, v_scale), v_limit);
    vst1q_f32(data + i, d0);
    vst1q_f32(data + i + 4, d1);
  }
#endif
  for (; i < size; ++i) {
    data[i] = std::min(data[i] * scale, ceiling_limit);
  }
}

WaveformBands WaveformDecimator::finalize() {
  normalizeBand(low_peaks_, 1.0f);
  normalizeBand(mid_peaks_, 0.82f);
  normalizeBand(high_peaks_, 0.5f);
  return {std::move(low_peaks_), std::move(mid_peaks_), std::move(high_peaks_)};
}

}  // namespace bitchord::smart