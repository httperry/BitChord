#include "vocal_mask_matcher.h"

#include <algorithm>
#include <cmath>
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace bitchord::smart {

namespace {

// Precomputed 256-entry table for (rmsFromLevel(level))^2
// Level [0.0 .. 1.0] maps to index [0 .. 255]
struct PowerTable {
  float table[256];
  PowerTable() {
    for (int i = 0; i < 256; ++i) {
      const double level = i / 255.0;
      if (level <= 0.0) {
        table[i] = 0.0f;
      } else {
        const double rms = std::pow(10.0, (level * 60.0 - 60.0) / 20.0);
        table[i] = static_cast<float>(rms * rms);
      }
    }
  }
};

const PowerTable kPowerLut;

}  // namespace

MaskStatsResult VocalMaskMatcher::computeMaskStats(
    const double* window_starts, const double* window_fps,
    const float* const* window_values, const size_t* window_sizes,
    size_t window_count, double from_sec, double to_sec) {
  MaskStatsResult result;
  if (to_sec <= from_sec || window_count == 0) return result;

  int total_frames = 0;
  int sung_frames = 0;
  int current_run = 0;
  int longest_run = 0;
  double covered_sec = 0.0;
  double last_fps = 1.0;

#if defined(__ARM_NEON) || defined(__aarch64__)
  const float32x4_t v_threshold = vdupq_n_f32(kThreshold);
#endif

  for (size_t w = 0; w < window_count; ++w) {
    const double start_t = window_starts[w];
    const double fps = window_fps[w];
    const size_t size = window_sizes[w];
    const float* values = window_values[w];

    const int a = std::max(0, static_cast<int>(std::ceil((from_sec - start_t) * fps)));
    const int b = std::min(static_cast<int>(size), static_cast<int>(std::floor((to_sec - start_t) * fps)));
    if (b <= a) continue;

    covered_sec += (b - a) / fps;
    last_fps = fps;

    int i = a;
#if defined(__ARM_NEON) || defined(__aarch64__)
    uint32x4_t v_sung_acc = vdupq_n_u32(0);

    for (; i + 8 <= b; i += 8) {
      float32x4_t val0 = vld1q_f32(values + i);
      float32x4_t val1 = vld1q_f32(values + i + 4);

      uint32x4_t m0 = vcgtq_f32(val0, v_threshold);
      uint32x4_t m1 = vcgtq_f32(val1, v_threshold);

      // Accumulate counts (m is 0xFFFFFFFF for true, so subtract)
      v_sung_acc = vsubq_u32(v_sung_acc, m0);
      v_sung_acc = vsubq_u32(v_sung_acc, m1);

      // Fast check for continuous run tracking
      const uint32_t min_mask = vminvq_u32(vandq_u32(m0, m1));
      const uint32_t max_mask = vmaxvq_u32(vorrq_u32(m0, m1));

      if (min_mask == 0xFFFFFFFF) {
        // All 8 frames are sung
        current_run += 8;
        longest_run = std::max(longest_run, current_run);
      } else if (max_mask == 0) {
        // All 8 frames are silent
        current_run = 0;
      } else {
        // Mixed: inspect 8 frames
        uint32_t bits0[4], bits1[4];
        vst1q_u32(bits0, m0);
        vst1q_u32(bits1, m1);
        for (int k = 0; k < 4; ++k) {
          if (bits0[k]) { ++current_run; longest_run = std::max(longest_run, current_run); }
          else { current_run = 0; }
        }
        for (int k = 0; k < 4; ++k) {
          if (bits1[k]) { ++current_run; longest_run = std::max(longest_run, current_run); }
          else { current_run = 0; }
        }
      }
    }
    sung_frames += vaddvq_u32(v_sung_acc);
    total_frames += (i - a);
#endif

    // Remainder scalar loop
    for (; i < b; ++i) {
      total_frames++;
      if (values[i] > kThreshold) {
        sung_frames++;
        current_run++;
        longest_run = std::max(longest_run, current_run);
      } else {
        current_run = 0;
      }
    }
  }

  if (total_frames > 0 && covered_sec >= (to_sec - from_sec) * kMinCoverage) {
    result.share = static_cast<double>(sung_frames) / total_frames;
    result.longest_run_sec = longest_run / last_fps;
    result.valid = true;
  }
  return result;
}

double VocalMaskMatcher::computeSimultaneousVocalFraction(
    const double* out_times, const float* out_mask, size_t out_count,
    const double* in_times, const float* in_mask, size_t in_count,
    double out_start, double out_end, double in_start, double rate) {
  if (out_count == 0 || in_count == 0 || out_end <= out_start) return -1.0;

  const double step = (rate > 0.0 && std::isfinite(rate)) ? rate : 1.0;
  size_t in_index = 0;
  int both = 0;
  int total = 0;

  for (size_t out_idx = 0; out_idx < out_count; ++out_idx) {
    const double time = out_times[out_idx];
    if (!std::isfinite(time) || time < out_start) continue;
    if (time > out_end) break;

    total++;
    if (out_mask[out_idx] < kThreshold) continue;

    const double target = in_start + (time - out_start) * step;
    while (in_index + 1 < in_count && in_times[in_index + 1] <= target) {
      in_index++;
    }

    if (in_mask[in_index] >= kThreshold) {
      both++;
    }
  }

  return total > 0 ? (static_cast<double>(both) / total) : -1.0;
}

double VocalMaskMatcher::computeWindowMeanRms(
    const double* window_starts, const double* window_fps,
    const float* const* window_values, const size_t* window_sizes,
    size_t window_count, double from_sec, double to_sec) {
  if (to_sec <= from_sec || window_count == 0) return NAN;

  double sum_squares = 0.0;
  int count = 0;

  for (size_t w = 0; w < window_count; ++w) {
    const double start_t = window_starts[w];
    const double fps = window_fps[w];
    const size_t size = window_sizes[w];
    const float* values = window_values[w];

    const int a = std::max(0, static_cast<int>(std::ceil((from_sec - start_t) * fps)));
    const int b = std::min(static_cast<int>(size), static_cast<int>(std::floor((to_sec - start_t) * fps)));
    if (b <= a) continue;

    size_t i = a;
#if defined(__ARM_NEON) || defined(__aarch64__)
    float32x4_t sum_vec0 = vdupq_n_f32(0.0f);
    float32x4_t sum_vec1 = vdupq_n_f32(0.0f);

    for (; i + 8 <= static_cast<size_t>(b); i += 8) {
      // Direct LUT lookup for level -> squared_rms
      float p0 = kPowerLut.table[std::clamp(static_cast<int>(values[i] * 255.0f), 0, 255)];
      float p1 = kPowerLut.table[std::clamp(static_cast<int>(values[i + 1] * 255.0f), 0, 255)];
      float p2 = kPowerLut.table[std::clamp(static_cast<int>(values[i + 2] * 255.0f), 0, 255)];
      float p3 = kPowerLut.table[std::clamp(static_cast<int>(values[i + 3] * 255.0f), 0, 255)];

      float p4 = kPowerLut.table[std::clamp(static_cast<int>(values[i + 4] * 255.0f), 0, 255)];
      float p5 = kPowerLut.table[std::clamp(static_cast<int>(values[i + 5] * 255.0f), 0, 255)];
      float p6 = kPowerLut.table[std::clamp(static_cast<int>(values[i + 6] * 255.0f), 0, 255)];
      float p7 = kPowerLut.table[std::clamp(static_cast<int>(values[i + 7] * 255.0f), 0, 255)];

      float32x4_t v0 = {p0, p1, p2, p3};
      float32x4_t v1 = {p4, p5, p6, p7};
      sum_vec0 = vaddq_f32(sum_vec0, v0);
      sum_vec1 = vaddq_f32(sum_vec1, v1);
    }
    sum_squares += vaddvq_f32(vaddq_f32(sum_vec0, sum_vec1));
    count += (i - a);
#endif
    for (; i < static_cast<size_t>(b); ++i) {
      const int idx = std::clamp(static_cast<int>(values[i] * 255.0f), 0, 255);
      sum_squares += kPowerLut.table[idx];
      count++;
    }
  }

  return count > 0 ? std::sqrt(sum_squares / count) : NAN;
}

}  // namespace bitchord::smart