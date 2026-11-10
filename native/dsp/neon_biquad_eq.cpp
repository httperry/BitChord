#include "neon_biquad_eq.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace bitchord::dsp {

NeonBiquadEq::NeonBiquadEq() {
  for (int ch = 0; ch < 2; ++ch) {
    for (int i = 0; i < kEqSlots; ++i) {
      target_qs_[ch][i] = 0.707f;
      current_qs_[ch][i] = 0.707f;
    }
  }
}

void NeonBiquadEq::Configure(int sample_rate, int channel_count) {
  sample_rate_ = sample_rate;
  channel_count_ = channel_count;

  // For stereo (channel_count == 2): 4 state floats per slot:
  // [slot * 4 + 0] = ic1_l, [slot * 4 + 1] = ic1_r
  // [slot * 4 + 2] = ic2_l, [slot * 4 + 3] = ic2_r
  // For other channel counts: channel_count * kEqSlots * 2
  const size_t state_size = (channel_count == 2)
      ? static_cast<size_t>(kEqSlots * 4)
      : static_cast<size_t>(channel_count * kEqSlots * 2);

  state_.assign(state_size, 0.0f);
  bass_swap_state_.assign(static_cast<size_t>(std::max(channel_count, 2) * 2), 0.0f);
  channel_gain_.assign(static_cast<size_t>(channel_count), 1.0f);

  if (sample_rate > 0) {
    for (int slot = 0; slot < kEqSlots; ++slot) {
      const float hz = UsableFrequency(kEqLayoutSlots[slot].frequencyHz);
      precomputed_base_tan_[slot] = std::tan(kPi * hz / static_cast<float>(sample_rate));
    }
    const float hz = UsableFrequency(target_bass_swap_hz_);
    bass_swap_base_tan_ = std::tan(kPi * hz / static_cast<float>(sample_rate));
  }
  std::memset(running_, 0, sizeof(running_));
  bass_swap_running_ = false;
  SnapToTarget();
}

void NeonBiquadEq::Reset() {
  state_.clear();
  bass_swap_state_.clear();
  channel_gain_.clear();
  std::fill(std::begin(precomputed_base_tan_), std::end(precomputed_base_tan_), 0.0f);
  std::memset(running_, 0, sizeof(running_));
  is_shaping_ = false;
  is_dual_channel_ = false;
  bass_swap_shelf_enabled_ = false;
  bass_swap_running_ = false;
  target_bass_swap_db_ = 0.0f;
  current_bass_swap_db_ = 0.0f;
  channel_count_ = 0;
  sample_rate_ = 0;
}

void NeonBiquadEq::Flush() {
  std::fill(state_.begin(), state_.end(), 0.0f);
  std::fill(bass_swap_state_.begin(), bass_swap_state_.end(), 0.0f);
  std::memset(running_, 0, sizeof(running_));
  current_bass_swap_db_ = target_bass_swap_db_;
  bass_swap_running_ = false;
  SnapToTarget();
}

void NeonBiquadEq::SetBassSwapShelf(bool enabled, float attenuation_db, float cutoff_hz) {
  bass_swap_shelf_enabled_ = enabled;
  target_bass_swap_db_ = enabled ? std::clamp(attenuation_db, -96.0f, 0.0f) : 0.0f;
  target_bass_swap_hz_ = cutoff_hz;
  if (sample_rate_ > 0) {
    const float hz = UsableFrequency(target_bass_swap_hz_);
    bass_swap_base_tan_ = std::tan(kPi * hz / static_cast<float>(sample_rate_));
  }
}

float NeonBiquadEq::CalculateRecommendedPreamp(const float* gains_db_l, const float* gains_db_r) {
  float max_boost = 0.0f;
  if (gains_db_l) {
    for (int i = 0; i < kEqSlots; ++i) {
      if (gains_db_l[i] > max_boost) max_boost = gains_db_l[i];
    }
  }
  if (gains_db_r) {
    for (int i = 0; i < kEqSlots; ++i) {
      if (gains_db_r[i] > max_boost) max_boost = gains_db_r[i];
    }
  }
  return -max_boost;
}

void NeonBiquadEq::SetTuning(bool enabled, const float* gains_db, const float* qs, float preamp_db, float balance) {
  SetDualTuning(enabled, gains_db, qs, gains_db, qs, preamp_db, balance);
}

void NeonBiquadEq::SetDualTuning(bool enabled,
                                 const float* gains_db_l, const float* qs_l,
                                 const float* gains_db_r, const float* qs_r,
                                 float preamp_db, float balance) {
  enabled_ = enabled;
  if (!enabled) {
    std::memset(target_gains_db_, 0, sizeof(target_gains_db_));
    for (int ch = 0; ch < 2; ++ch) {
      for (int i = 0; i < kEqSlots; ++i) target_qs_[ch][i] = 0.707f;
    }
    target_preamp_db_ = 0.0f;
    target_balance_ = 0.0f;
    is_dual_channel_ = false;
  } else {
    if (gains_db_l) {
      std::memcpy(target_gains_db_[0], gains_db_l, sizeof(target_gains_db_[0]));
    } else {
      std::memset(target_gains_db_[0], 0, sizeof(target_gains_db_[0]));
    }
    if (qs_l) {
      std::memcpy(target_qs_[0], qs_l, sizeof(target_qs_[0]));
    } else {
      for (int i = 0; i < kEqSlots; ++i) target_qs_[0][i] = 0.707f;
    }

    if (gains_db_r) {
      std::memcpy(target_gains_db_[1], gains_db_r, sizeof(target_gains_db_[1]));
    } else {
      std::memset(target_gains_db_[1], 0, sizeof(target_gains_db_[1]));
    }
    if (qs_r) {
      std::memcpy(target_qs_[1], qs_r, sizeof(target_qs_[1]));
    } else {
      for (int i = 0; i < kEqSlots; ++i) target_qs_[1][i] = 0.707f;
    }

    is_dual_channel_ = false;
    for (int i = 0; i < kEqSlots; ++i) {
      if (std::abs(target_gains_db_[0][i] - target_gains_db_[1][i]) >= kSettledDb ||
          std::abs(target_qs_[0][i] - target_qs_[1][i]) >= 0.01f) {
        is_dual_channel_ = true;
        break;
      }
    }

    float max_boost = 0.0f;
    for (int i = 0; i < kEqSlots; ++i) {
      if (target_gains_db_[0][i] > max_boost) max_boost = target_gains_db_[0][i];
      if (target_gains_db_[1][i] > max_boost) max_boost = target_gains_db_[1][i];
    }

    // Apply pre-amp attenuation to avoid digital overs and zero audio clipping
    if (preamp_db <= -kSettledDb) {
      target_preamp_db_ = preamp_db;
    } else if (max_boost >= kSettledDb) {
      target_preamp_db_ = -max_boost;
    } else {
      target_preamp_db_ = preamp_db;
    }

    target_balance_ = std::clamp(balance, -1.0f, 1.0f);
  }
}

void NeonBiquadEq::SnapToTarget() {
  std::memcpy(current_gains_db_, target_gains_db_, sizeof(current_gains_db_));
  std::memcpy(current_qs_, target_qs_, sizeof(current_qs_));
  current_preamp_db_ = target_preamp_db_;
  current_balance_ = target_balance_;
  current_bass_swap_db_ = target_bass_swap_db_;
  current_bass_swap_hz_ = target_bass_swap_hz_;
}

float NeonBiquadEq::UsableFrequency(float hz) const {
  const float max_hz = static_cast<float>(sample_rate_) * kMaxFreqFraction;
  return std::clamp(hz, kMinHz, max_hz);
}

bool NeonBiquadEq::IsFlat() const {
  if (std::abs(target_balance_) >= kSettledBalance) return false;
  if (std::abs(target_preamp_db_) >= kSettledDb) return false;
  if (bass_swap_shelf_enabled_ || std::abs(target_bass_swap_db_) >= kSettledDb) return false;
  for (int ch = 0; ch < 2; ++ch) {
    for (int i = 0; i < kEqSlots; ++i) {
      if (std::abs(target_gains_db_[ch][i]) >= kSettledDb) return false;
    }
  }
  return true;
}

bool NeonBiquadEq::IsSettled() const {
  if (std::abs(current_balance_ - target_balance_) >= kSettledBalance) return false;
  if (std::abs(current_preamp_db_ - target_preamp_db_) >= kSettledDb) return false;
  if (std::abs(current_bass_swap_db_ - target_bass_swap_db_) >= kSettledDb) return false;
  for (int ch = 0; ch < 2; ++ch) {
    for (int i = 0; i < kEqSlots; ++i) {
      if (std::abs(current_gains_db_[ch][i] - target_gains_db_[ch][i]) >= kSettledDb) return false;
    }
  }
  return true;
}

void NeonBiquadEq::GlideTowards() {
  for (int ch = 0; ch < 2; ++ch) {
    for (int i = 0; i < kEqSlots; ++i) {
      current_gains_db_[ch][i] += (target_gains_db_[ch][i] - current_gains_db_[ch][i]) * kGlideRate;
      const float from_q = std::log(std::max(current_qs_[ch][i], kMinQ));
      const float to_q = std::log(std::max(target_qs_[ch][i], kMinQ));
      current_qs_[ch][i] = std::exp(from_q + (to_q - from_q) * kGlideRate);
    }
  }
  current_preamp_db_ += (target_preamp_db_ - current_preamp_db_) * kGlideRate;
  current_balance_ += (target_balance_ - current_balance_) * kGlideRate;
  current_bass_swap_db_ += (target_bass_swap_db_ - current_bass_swap_db_) * kGlideRate;
}

int NeonBiquadEq::PrepareSections() {
  int active = 0;
  for (int slot = 0; slot < kEqSlots; ++slot) {
    const bool active_l = std::abs(current_gains_db_[0][slot]) >= kSettledDb;
    const bool active_r = std::abs(current_gains_db_[1][slot]) >= kSettledDb;

    if (active_l || active_r) {
      UpdateCoefficients(slot, 0);
      UpdateCoefficients(slot, 1);
      active_slots_[active++] = slot;
      running_[0][slot] = true;
      running_[1][slot] = true;
    } else {
      if (running_[0][slot] || running_[1][slot]) {
        if (channel_count_ == 2) {
          state_[slot * 4 + 0] = 0.0f;
          state_[slot * 4 + 1] = 0.0f;
          state_[slot * 4 + 2] = 0.0f;
          state_[slot * 4 + 3] = 0.0f;
        } else {
          for (int c = 0; c < channel_count_; ++c) {
            const size_t i = static_cast<size_t>((c * kEqSlots + slot) * 2);
            if (i + 1 < state_.size()) {
              state_[i] = 0.0f;
              state_[i + 1] = 0.0f;
            }
          }
        }
        running_[0][slot] = false;
        running_[1][slot] = false;
      }
    }
  }
  return active;
}

void NeonBiquadEq::UpdateBassSwapCoefficients() {
  if (sample_rate_ <= 0) return;
  const float a = std::pow(10.0f, current_bass_swap_db_ / 40.0f);
  const float q = 0.707f;
  const float base = bass_swap_base_tan_;
  const float g = base / std::sqrt(a);
  const float k = 1.0f / q;
  bass_swap_mix_in_ = 1.0f;
  bass_swap_mix_band_ = k * (a - 1.0f);
  bass_swap_mix_low_ = a * a - 1.0f;
  const float d = 1.0f / (1.0f + g * (g + k));
  bass_swap_a1_ = d;
  bass_swap_a2_ = g * d;
  bass_swap_a3_ = g * (g * d);
}

void NeonBiquadEq::UpdateCoefficients(int slot, int ch) {
  if (sample_rate_ <= 0) return;
  const auto& spec = kEqLayoutSlots[slot];
  const float gain = current_gains_db_[ch][slot];
  const float a = std::pow(10.0f, gain / 40.0f);
  const float q = std::max(current_qs_[ch][slot], kMinQ);
  const float base = precomputed_base_tan_[slot];

  float g = 0.0f;
  float k = 0.0f;
  float m_in = 1.0f;
  float m_band = 0.0f;
  float m_low = 0.0f;

  switch (spec.kind) {
    case FilterKind::BELL:
      g = base;
      k = 1.0f / (q * a);
      m_in = 1.0f;
      m_band = k * (a * a - 1.0f);
      m_low = 0.0f;
      break;
    case FilterKind::LOW_SHELF:
      g = base / std::sqrt(a);
      k = 1.0f / q;
      m_in = 1.0f;
      m_band = k * (a - 1.0f);
      m_low = a * a - 1.0f;
      break;
    case FilterKind::HIGH_SHELF:
      g = base * std::sqrt(a);
      k = 1.0f / q;
      m_in = a * a;
      m_band = k * (1.0f - a) * a;
      m_low = 1.0f - a * a;
      break;
  }
  const float d = 1.0f / (1.0f + g * (g + k));
  packed_coeff_a1_[slot][ch] = d;
  packed_coeff_a2_[slot][ch] = g * d;
  packed_coeff_a3_[slot][ch] = g * (g * d);
  packed_mix_in_[slot][ch] = m_in;
  packed_mix_band_[slot][ch] = m_band;
  packed_mix_low_[slot][ch] = m_low;
}

void NeonBiquadEq::PrepareChannelGains() {
  const float preamp = std::pow(10.0f, current_preamp_db_ / 20.0f);
  if (channel_count_ == 2) {
    channel_gain_[0] = preamp * std::min(1.0f, 1.0f - current_balance_);
    channel_gain_[1] = preamp * std::min(1.0f, 1.0f + current_balance_);
  } else {
    std::fill(channel_gain_.begin(), channel_gain_.end(), preamp);
  }
}

void NeonBiquadEq::Process(float* samples, int frame_count) {
  if (frame_count <= 0 || channel_count_ < 1 || sample_rate_ <= 0) return;

  const bool bass_swap_active_or_shaping =
      bass_swap_shelf_enabled_ ||
      std::abs(current_bass_swap_db_) >= kSettledDb ||
      std::abs(target_bass_swap_db_) >= kSettledDb;

  if (IsFlat() && IsSettled() && !bass_swap_active_or_shaping) {
    is_shaping_ = false;
    return;
  }
  is_shaping_ = true;
  EnableHardwareFlushToZero();

  int remaining = frame_count;
  int frame_offset = 0;

  while (remaining > 0) {
    const int sub_block = std::min(remaining, kGlideFrames);
    GlideTowards();
    const int active_count = PrepareSections();
    PrepareChannelGains();

    const bool bass_swap_active = std::abs(current_bass_swap_db_) >= kSettledDb;
    if (bass_swap_active) {
      UpdateBassSwapCoefficients();
      bass_swap_running_ = true;
    } else if (bass_swap_running_) {
      std::fill(bass_swap_state_.begin(), bass_swap_state_.end(), 0.0f);
      bass_swap_running_ = false;
    }

#if defined(__ARM_NEON) || defined(__aarch64__)
    // High-performance vectorized stereo path (channel_count == 2)
    // Left (lane 0) and Right (lane 1) are processed simultaneously in NEON 64-bit registers
    if (channel_count_ == 2) {
      const float32x2_t v_gain = vld1_f32(channel_gain_.data());
      float* block_ptr = samples + (frame_offset * 2);

      for (int f = 0; f < sub_block; ++f) {
        float32x2_t v_x = vld1_f32(block_ptr + f * 2);

        for (int idx = 0; idx < active_count; ++idx) {
          const int slot = active_slots_[idx];
          const float32x2_t v_a1 = vld1_f32(packed_coeff_a1_[slot]);
          const float32x2_t v_a2 = vld1_f32(packed_coeff_a2_[slot]);
          const float32x2_t v_a3 = vld1_f32(packed_coeff_a3_[slot]);
          const float32x2_t v_m_in = vld1_f32(packed_mix_in_[slot]);
          const float32x2_t v_m_band = vld1_f32(packed_mix_band_[slot]);
          const float32x2_t v_m_low = vld1_f32(packed_mix_low_[slot]);

          float32x2_t v_ic1 = vld1_f32(&state_[slot * 4 + 0]);
          float32x2_t v_ic2 = vld1_f32(&state_[slot * 4 + 2]);

          const float32x2_t v3 = vsub_f32(v_x, v_ic2);
          float32x2_t v1 = vmul_f32(v_a1, v_ic1);
          v1 = vfma_f32(v1, v_a2, v3);

          float32x2_t v2 = v_ic2;
          v2 = vfma_f32(v2, v_a2, v_ic1);
          v2 = vfma_f32(v2, v_a3, v3);

          v_ic1 = vsub_f32(vadd_f32(v1, v1), v_ic1);
          v_ic2 = vsub_f32(vadd_f32(v2, v2), v_ic2);

          vst1_f32(&state_[slot * 4 + 0], v_ic1);
          vst1_f32(&state_[slot * 4 + 2], v_ic2);

          float32x2_t v_y = vmul_f32(v_m_in, v_x);
          v_y = vfma_f32(v_y, v_m_band, v1);
          v_x = vfma_f32(v_y, v_m_low, v2);
        }

        if (bass_swap_active) {
          const float32x2_t bs_a1 = vdup_n_f32(bass_swap_a1_);
          const float32x2_t bs_a2 = vdup_n_f32(bass_swap_a2_);
          const float32x2_t bs_a3 = vdup_n_f32(bass_swap_a3_);
          const float32x2_t bs_m_in = vdup_n_f32(bass_swap_mix_in_);
          const float32x2_t bs_m_band = vdup_n_f32(bass_swap_mix_band_);
          const float32x2_t bs_m_low = vdup_n_f32(bass_swap_mix_low_);

          float32x2_t bs_ic1 = vld1_f32(&bass_swap_state_[0]);
          float32x2_t bs_ic2 = vld1_f32(&bass_swap_state_[2]);

          const float32x2_t bs_v3 = vsub_f32(v_x, bs_ic2);
          float32x2_t bs_v1 = vmul_f32(bs_a1, bs_ic1);
          bs_v1 = vfma_f32(bs_v1, bs_a2, bs_v3);

          float32x2_t bs_v2 = bs_ic2;
          bs_v2 = vfma_f32(bs_v2, bs_a2, bs_ic1);
          bs_v2 = vfma_f32(bs_v2, bs_a3, bs_v3);

          bs_ic1 = vsub_f32(vadd_f32(bs_v1, bs_v1), bs_ic1);
          bs_ic2 = vsub_f32(vadd_f32(bs_v2, bs_v2), bs_ic2);

          vst1_f32(&bass_swap_state_[0], bs_ic1);
          vst1_f32(&bass_swap_state_[2], bs_ic2);

          float32x2_t bs_y = vmul_f32(bs_m_in, v_x);
          bs_y = vfma_f32(bs_y, bs_m_band, bs_v1);
          v_x = vfma_f32(bs_y, bs_m_low, bs_v2);
        }

        v_x = vmul_f32(v_x, v_gain);
        vst1_f32(block_ptr + f * 2, v_x);
      }
    } else if (channel_count_ == 4) {
      // 4-channel SIMD path using vfmaq_f32 across all 4 channels simultaneously
      const float32x4_t v_gains = vld1q_f32(channel_gain_.data());
      float* block_ptr = samples + (frame_offset * 4);

      for (int f = 0; f < sub_block; ++f) {
        float32x4_t x = vld1q_f32(block_ptr + f * 4);

        for (int idx = 0; idx < active_count; ++idx) {
          const int slot = active_slots_[idx];
          // Ch 0 and 2 use Left coefficients, Ch 1 and 3 use Right coefficients
          const float32x4_t a1 = {packed_coeff_a1_[slot][0], packed_coeff_a1_[slot][1],
                                  packed_coeff_a1_[slot][0], packed_coeff_a1_[slot][1]};
          const float32x4_t a2 = {packed_coeff_a2_[slot][0], packed_coeff_a2_[slot][1],
                                  packed_coeff_a2_[slot][0], packed_coeff_a2_[slot][1]};
          const float32x4_t a3 = {packed_coeff_a3_[slot][0], packed_coeff_a3_[slot][1],
                                  packed_coeff_a3_[slot][0], packed_coeff_a3_[slot][1]};
          const float32x4_t m_in = {packed_mix_in_[slot][0], packed_mix_in_[slot][1],
                                    packed_mix_in_[slot][0], packed_mix_in_[slot][1]};
          const float32x4_t m_band = {packed_mix_band_[slot][0], packed_mix_band_[slot][1],
                                      packed_mix_band_[slot][0], packed_mix_band_[slot][1]};
          const float32x4_t m_low = {packed_mix_low_[slot][0], packed_mix_low_[slot][1],
                                     packed_mix_low_[slot][0], packed_mix_low_[slot][1]};

          // Gather ic1 and ic2 across channels 0..3
          float32x4_t ic1 = {state_[slot * 2],
                             state_[(kEqSlots + slot) * 2],
                             state_[(2 * kEqSlots + slot) * 2],
                             state_[(3 * kEqSlots + slot) * 2]};
          float32x4_t ic2 = {state_[slot * 2 + 1],
                             state_[(kEqSlots + slot) * 2 + 1],
                             state_[(2 * kEqSlots + slot) * 2 + 1],
                             state_[(3 * kEqSlots + slot) * 2 + 1]};

          const float32x4_t v3 = vsubq_f32(x, ic2);
          float32x4_t v1 = vmulq_f32(a1, ic1);
          v1 = vfmaq_f32(v1, a2, v3);

          float32x4_t v2 = ic2;
          v2 = vfmaq_f32(v2, a2, ic1);
          v2 = vfmaq_f32(v2, a3, v3);

          ic1 = vfmaq_n_f32(vnegq_f32(ic1), v1, 2.0f);
          ic2 = vfmaq_n_f32(vnegq_f32(ic2), v2, 2.0f);

          state_[slot * 2] = vgetq_lane_f32(ic1, 0);
          state_[(kEqSlots + slot) * 2] = vgetq_lane_f32(ic1, 1);
          state_[(2 * kEqSlots + slot) * 2] = vgetq_lane_f32(ic1, 2);
          state_[(3 * kEqSlots + slot) * 2] = vgetq_lane_f32(ic1, 3);

          state_[slot * 2 + 1] = vgetq_lane_f32(ic2, 0);
          state_[(kEqSlots + slot) * 2 + 1] = vgetq_lane_f32(ic2, 1);
          state_[(2 * kEqSlots + slot) * 2 + 1] = vgetq_lane_f32(ic2, 2);
          state_[(3 * kEqSlots + slot) * 2 + 1] = vgetq_lane_f32(ic2, 3);

          float32x4_t y = vmulq_f32(m_in, x);
          y = vfmaq_f32(y, m_band, v1);
          x = vfmaq_f32(y, m_low, v2);
        }

        if (bass_swap_active) {
          const float32x4_t bs_a1 = vdupq_n_f32(bass_swap_a1_);
          const float32x4_t bs_a2 = vdupq_n_f32(bass_swap_a2_);
          const float32x4_t bs_a3 = vdupq_n_f32(bass_swap_a3_);
          const float32x4_t bs_in = vdupq_n_f32(bass_swap_mix_in_);
          const float32x4_t bs_band = vdupq_n_f32(bass_swap_mix_band_);
          const float32x4_t bs_low = vdupq_n_f32(bass_swap_mix_low_);

          float32x4_t bs_ic1 = {bass_swap_state_[0], bass_swap_state_[2], bass_swap_state_[4], bass_swap_state_[6]};
          float32x4_t bs_ic2 = {bass_swap_state_[1], bass_swap_state_[3], bass_swap_state_[5], bass_swap_state_[7]};

          const float32x4_t bs_v3 = vsubq_f32(x, bs_ic2);
          float32x4_t bs_v1 = vmulq_f32(bs_a1, bs_ic1);
          bs_v1 = vfmaq_f32(bs_v1, bs_a2, bs_v3);

          float32x4_t bs_v2 = bs_ic2;
          bs_v2 = vfmaq_f32(bs_v2, bs_a2, bs_ic1);
          bs_v2 = vfmaq_f32(bs_v2, bs_a3, bs_v3);

          bs_ic1 = vfmaq_n_f32(vnegq_f32(bs_ic1), bs_v1, 2.0f);
          bs_ic2 = vfmaq_n_f32(vnegq_f32(bs_ic2), bs_v2, 2.0f);

          bass_swap_state_[0] = vgetq_lane_f32(bs_ic1, 0);
          bass_swap_state_[2] = vgetq_lane_f32(bs_ic1, 1);
          bass_swap_state_[4] = vgetq_lane_f32(bs_ic1, 2);
          bass_swap_state_[6] = vgetq_lane_f32(bs_ic1, 3);

          bass_swap_state_[1] = vgetq_lane_f32(bs_ic2, 0);
          bass_swap_state_[3] = vgetq_lane_f32(bs_ic2, 1);
          bass_swap_state_[5] = vgetq_lane_f32(bs_ic2, 2);
          bass_swap_state_[7] = vgetq_lane_f32(bs_ic2, 3);

          float32x4_t bs_y = vmulq_f32(bs_in, x);
          bs_y = vfmaq_f32(bs_y, bs_band, bs_v1);
          x = vfmaq_f32(bs_y, bs_low, bs_v2);
        }

        x = vmulq_f32(x, v_gains);
        vst1q_f32(block_ptr + f * 4, x);
      }
    } else {
#endif
      // Scalar fallback for generic channel counts
      for (int f = 0; f < sub_block; ++f) {
        const int base_idx = (frame_offset + f) * channel_count_;
        for (int ch = 0; ch < channel_count_; ++ch) {
          const int coeff_ch = (ch < 2) ? ch : 0;
          float sample = samples[base_idx + ch];
          for (int idx = 0; idx < active_count; ++idx) {
            const int slot = active_slots_[idx];
            const size_t i = (channel_count_ == 2)
                ? static_cast<size_t>(slot * 4 + (ch == 0 ? 0 : 1))
                : static_cast<size_t>((ch * kEqSlots + slot) * 2);
            const size_t i2 = (channel_count_ == 2)
                ? static_cast<size_t>(slot * 4 + 2 + (ch == 0 ? 0 : 1))
                : i + 1;
            const float ic1 = state_[i];
            const float ic2 = state_[i2];
            const float v3 = sample - ic2;
            const float v1 = packed_coeff_a1_[slot][coeff_ch] * ic1 + packed_coeff_a2_[slot][coeff_ch] * v3;
            const float v2 = ic2 + packed_coeff_a2_[slot][coeff_ch] * ic1 + packed_coeff_a3_[slot][coeff_ch] * v3;
            state_[i] = 2.0f * v1 - ic1;
            state_[i2] = 2.0f * v2 - ic2;
            sample = packed_mix_in_[slot][coeff_ch] * sample +
                     packed_mix_band_[slot][coeff_ch] * v1 +
                     packed_mix_low_[slot][coeff_ch] * v2;
          }
          if (bass_swap_active) {
            const size_t bs_i = (channel_count_ == 2) ? (ch == 0 ? 0 : 1) : static_cast<size_t>(ch * 2);
            const size_t bs_i2 = (channel_count_ == 2) ? (ch == 0 ? 2 : 3) : bs_i + 1;
            const float bs_ic1 = bass_swap_state_[bs_i];
            const float bs_ic2 = bass_swap_state_[bs_i2];
            const float bs_v3 = sample - bs_ic2;
            const float bs_v1 = bass_swap_a1_ * bs_ic1 + bass_swap_a2_ * bs_v3;
            const float bs_v2 = bs_ic2 + bass_swap_a2_ * bs_ic1 + bass_swap_a3_ * bs_v3;
            bass_swap_state_[bs_i] = 2.0f * bs_v1 - bs_ic1;
            bass_swap_state_[bs_i2] = 2.0f * bs_v2 - bs_ic2;
            sample = bass_swap_mix_in_ * sample + bass_swap_mix_band_ * bs_v1 + bass_swap_mix_low_ * bs_v2;
          }
          samples[base_idx + ch] = sample * channel_gain_[ch];
        }
      }
#if defined(__ARM_NEON) || defined(__aarch64__)
    }
#endif

    frame_offset += sub_block;
    remaining -= sub_block;
  }
}

}  // namespace bitchord::dsp