#pragma once

#include "neon_dsp_types.h"
#include <vector>

namespace bitchord::dsp {

class alignas(16) NeonSpatializer {
 public:
  NeonSpatializer();
  ~NeonSpatializer() = default;

  void Configure(int sample_rate, int channel_count);
  void Reset();
  void Flush();
  void SetEnabled(bool enabled) { enabled_ = enabled; }
  bool IsEnabled() const { return enabled_; }

  // Processes interleaved stereo float samples in-place
  void Process(float* samples, int frame_count);

 private:
  static constexpr int kDelayMs = 15;
  static constexpr float kWidthGain = 2.5f;
  static constexpr float kOutputGain = 0.82f;
  static constexpr float kCrossfeedGain = 0.2f;
  static constexpr float kLowpassCoeff = 0.3f;

  bool enabled_ = false;
  int sample_rate_ = 0;
  int channel_count_ = 0;

  std::vector<float> delay_left_;
  std::vector<float> delay_right_;
  int delay_index_ = 0;
  int delay_mask_ = 0;
  float lowpass_left_ = 0.0f;
  float lowpass_right_ = 0.0f;
};

}  // namespace bitchord::dsp