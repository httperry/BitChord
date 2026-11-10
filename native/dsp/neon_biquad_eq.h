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

  // Updates target parameters from UI thread (single/stereo-linked)
  void SetTuning(bool enabled, const float* gains_db, const float* qs, float preamp_db, float balance);

  // Independent Left and Right channel tuning (per-ear personalization)
  void SetDualTuning(bool enabled,
                     const float* gains_db_l, const float* qs_l,
                     const float* gains_db_r, const float* qs_r,
                     float preamp_db = 0.0f, float balance = 0.0f);

  // Low-shelf crossover attenuation (<150Hz) / bass swap shelf support
  void SetBassSwapShelf(bool enabled, float attenuation_db = -24.0f, float cutoff_hz = 150.0f);
  bool IsBassSwapShelfEnabled() const { return bass_swap_shelf_enabled_; }

  // In-place processing of interleaved float samples
  // Preserves full dynamic headroom
  void Process(float* samples, int frame_count);

  bool IsShaping() const { return is_shaping_ || bass_swap_shelf_enabled_ || std::abs(current_bass_swap_db_) >= kSettledDb; }
  bool IsEnabled() const { return enabled_ || bass_swap_shelf_enabled_; }
  bool IsDualChannel() const { return is_dual_channel_; }

  const float* GetTargetGains(int ch) const { return target_gains_db_[ch < 2 ? ch : 0]; }
  const float* GetCurrentGains(int ch) const { return current_gains_db_[ch < 2 ? ch : 0]; }
  float GetTargetPreampDb() const { return target_preamp_db_; }

  static float CalculateRecommendedPreamp(const float* gains_db_l, const float* gains_db_r);

 private:
  void SnapToTarget();
  void GlideTowards();
  int PrepareSections();
  void UpdateCoefficients(int slot, int ch);
  void UpdateBassSwapCoefficients();
  void PrepareChannelGains();
  float UsableFrequency(float hz) const;
  bool IsFlat() const;
  bool IsSettled() const;

  int sample_rate_ = 0;
  int channel_count_ = 0;
  bool enabled_ = false;
  bool is_shaping_ = false;
  bool is_dual_channel_ = false;

  // Bass swap low-shelf (<150Hz) state
  bool bass_swap_shelf_enabled_ = false;
  float target_bass_swap_db_ = 0.0f;
  float target_bass_swap_hz_ = 150.0f;
  float current_bass_swap_db_ = 0.0f;
  float current_bass_swap_hz_ = 150.0f;
  bool bass_swap_running_ = false;

  float bass_swap_a1_ = 0.0f;
  float bass_swap_a2_ = 0.0f;
  float bass_swap_a3_ = 0.0f;
  float bass_swap_mix_in_ = 1.0f;
  float bass_swap_mix_band_ = 0.0f;
  float bass_swap_mix_low_ = 0.0f;
  float bass_swap_base_tan_ = 0.0f;

  std::vector<float> bass_swap_state_;

  // Targets per channel (0: Left, 1: Right)
  float target_gains_db_[2][kEqSlots] = {{0}};
  float target_qs_[2][kEqSlots] = {{0}};
  float target_preamp_db_ = 0.0f;
  float target_balance_ = 0.0f;

  // Running values for gliding
  float current_gains_db_[2][kEqSlots] = {{0}};
  float current_qs_[2][kEqSlots] = {{0}};
  float current_preamp_db_ = 0.0f;
  float current_balance_ = 0.0f;

  // Coefficients (vector-ready alignment)
  // Packed as [slot][ch] where ch=0 is Left, ch=1 is Right
  // Aligned for 64-bit / 128-bit NEON SIMD access (vld1_f32)
  alignas(16) float packed_coeff_a1_[kEqSlots][2] = {{0}};
  alignas(16) float packed_coeff_a2_[kEqSlots][2] = {{0}};
  alignas(16) float packed_coeff_a3_[kEqSlots][2] = {{0}};
  alignas(16) float packed_mix_in_[kEqSlots][2] = {{0}};
  alignas(16) float packed_mix_band_[kEqSlots][2] = {{0}};
  alignas(16) float packed_mix_low_[kEqSlots][2] = {{0}};
  float precomputed_base_tan_[kEqSlots] = {0};

  int active_slots_[kEqSlots] = {0};
  bool running_[2][kEqSlots] = {{false}};

  // State: For stereo (channel_count == 2):
  // Aligned as [slot * 4 + 0] = ic1_l, [slot * 4 + 1] = ic1_r,
  //            [slot * 4 + 2] = ic2_l, [slot * 4 + 3] = ic2_r
  // Aligned for 64-bit NEON SIMD vld1_f32 / vst1_f32
  // For multi-channel: channel * kEqSlots * 2
  std::vector<float> state_;
  std::vector<float> channel_gain_;
};

}  // namespace bitchord::dsp