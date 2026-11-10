#pragma once

#include <cstddef>
#include <vector>

namespace bitchord::smart {

struct MaskStatsResult {
  double share = 0.0;
  double longest_run_sec = 0.0;
  bool valid = false;
};

class VocalMaskMatcher {
 public:
  static constexpr float kThreshold = 0.25f;
  static constexpr double kMinCoverage = 0.80;

  // Vectorized vocal mask stats calculation
  static MaskStatsResult computeMaskStats(
      const double* window_starts, const double* window_fps,
      const float* const* window_values, const size_t* window_sizes,
      size_t window_count, double from_sec, double to_sec);

  // Vectorized simultaneous dual-track vocal fraction
  static double computeSimultaneousVocalFraction(
      const double* out_times, const float* out_mask, size_t out_count,
      const double* in_times, const float* in_mask, size_t in_count,
      double out_start, double out_end, double in_start, double rate);

  // Vectorized mean RMS across curve windows (replaces scalar Math.pow in LoopMix)
  static double computeWindowMeanRms(
      const double* window_starts, const double* window_fps,
      const float* const* window_values, const size_t* window_sizes,
      size_t window_count, double from_sec, double to_sec);
};

}  // namespace bitchord::smart