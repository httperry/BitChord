#pragma once

#include "neon_dsp_types.h"

namespace bitchord::dsp {

class alignas(16) NeonLimiter {
 public:
  NeonLimiter();
  ~NeonLimiter() = default;

  void Configure(int sample_rate, int channel_count);
  void Reset();
  void SetParameters(float target_gain, float target_trim, bool hold_limiter, bool snap);

  // In-place processing with peak tracking and dynamic release
  void Process(float* samples, int frame_count);

  bool IsParked() const;

 private:
  static constexpr float kLimit = 0.985f;          // Ceiling just under full scale
  static constexpr float kGlideSeconds = 0.04f;    // Glide time constant
  static constexpr float kReleaseSeconds = 0.08f;  // Limiter release time constant
  static constexpr float kSettled = 1e-4f;
  static constexpr float kParkReduction = 0.97f;

  int sample_rate_ = 0;
  int channel_count_ = 0;
  bool hold_limiter_ = false;

  float target_gain_ = 1.0f;
  float target_trim_ = 1.0f;
  float current_gain_ = 1.0f;
  float current_trim_ = 1.0f;
  float reduction_ = 1.0f;

  float glide_coef_ = 1.0f;
  float release_coef_ = 1.0f;
};

}  // namespace bitchord::dsp