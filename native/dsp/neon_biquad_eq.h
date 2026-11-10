#pragma once

#include "neon_dsp_types.h"
#include <vector>

namespace bitchord::dsp {

class alignas(16) NeonBiquadEq {
 public:
  NeonBiquadEq();
  ~NeonBiquadEq() = default;

  void Configure(int sample_rate, int channel_count);
  void Reset();
  void Flush();

  // Updates target parameters from UI thread
  void SetTuning(bool enabled, const float* gains_db, const float* qs, float preamp_db, float balance);

  // In-place processing of interleaved float samples
  // Preserves full dynamic headroom
  void Process(float* samples, int frame_count);

  bool IsShaping() const { return is_shaping_; }
  bool IsEnabled() const { return enabled_; }

 private:
  void SnapToTarget();
  void GlideTowards();
  int PrepareSections();
  void UpdateCoefficients(int slot);
  void PrepareChannelGains();
  float UsableFrequency(float hz) const;
  bool IsFlat() const;
  bool IsSettled() const;

  int sample_rate_ = 0;
  int channel_count_ = 0;
  bool enabled_ = false;
  bool is_shaping_ = false;

  // Targets
  float target_gains_db_[kEqSlots] = {0};
  float target_qs_[kEqSlots] = {0};
  float target_preamp_db_ = 0.0f;
  float target_balance_ = 0.0f;

  // Running values for gliding
  float current_gains_db_[kEqSlots] = {0};
  float current_qs_[kEqSlots] = {0};
  float current_preamp_db_ = 0.0f;
  float current_balance_ = 0.0f;

  // Coefficients (vector-ready alignment)
  alignas(16) float coeff_a1_[kEqSlots] = {0};
  alignas(16) float coeff_a2_[kEqSlots] = {0};
  alignas(16) float coeff_a3_[kEqSlots] = {0};
  alignas(16) float mix_in_[kEqSlots] = {0};
  alignas(16) float mix_band_[kEqSlots] = {0};
  alignas(16) float mix_low_[kEqSlots] = {0};
  float precomputed_base_tan_[kEqSlots] = {0};

  int active_slots_[kEqSlots] = {0};
  bool running_[kEqSlots] = {false};

  // State: 2 integrators per section per channel (channel * kEqSlots + slot) * 2
  // Aligned for 128-bit NEON SIMD access
  std::vector<float> state_;
  std::vector<float> channel_gain_;
};

}  // namespace bitchord::dsp