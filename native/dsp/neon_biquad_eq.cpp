#include "neon_biquad_eq.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace bitchord::dsp {

NeonBiquadEq::NeonBiquadEq() {
  for (int i = 0; i < kEqSlots; ++i) {
    target_qs_[i] = 0.707f;
    current_qs_[i] = 0.707f;
  }
}

void NeonBiquadEq::Configure(int sample_rate, int channel_count) {
  sample_rate_ = sample_rate;
  channel_count_ = channel_count;
  const size_t state_size = static_cast<size_t>(channel_count * kEqSlots * 2);
  state_.assign(state_size, 0.0f);
  channel_gain_.assign(static_cast<size_t>(channel_count), 1.0f);

  if (sample_rate > 0) {
    for (int slot = 0; slot < kEqSlots; ++slot) {
      const float hz = UsableFrequency(kEqLayoutSlots[slot].frequencyHz);
      precomputed_base_tan_[slot] = std::tan(kPi * hz / static_cast<float>(sample_rate));
    }
  }
  std::fill(std::begin(running_), std::end(running_), false);
  SnapToTarget();
}

void NeonBiquadEq::Reset() {
  state_.clear();
  channel_gain_.clear();
  std::fill(std::begin(precomputed_base_tan_), std::end(precomputed_base_tan_), 0.0f);
  std::fill(std::begin(running_), std::end(running_), false);
  is_shaping_ = false;
  channel_count_ = 0;
  sample_rate_ = 0;
}

void NeonBiquadEq::Flush() {
  std::fill(state_.begin(), state_.end(), 0.0f);
  std::fill(std::begin(running_), std::end(running_), false);
  SnapToTarget();
}

void NeonBiquadEq::SetTuning(bool enabled, const float* gains_db, const float* qs, float preamp_db, float balance) {
  enabled_ = enabled;
  if (!enabled) {
    std::fill(std::begin(target_gains_db_), std::end(target_gains_db_), 0.0f);
    for (int i = 0; i < kEqSlots; ++i) target_qs_[i] = 0.707f;
    target_preamp_db_ = 0.0f;
    target_balance_ = 0.0f;
  } else {
    std::memcpy(target_gains_db_, gains_db, sizeof(target_gains_db_));
    std::memcpy(target_qs_, qs, sizeof(target_qs_));
    target_preamp_db_ = preamp_db;
    target_balance_ = std::clamp(balance, -1.0f, 1.0f);
  }
}

void NeonBiquadEq::SnapToTarget() {
  std::memcpy(current_gains_db_, target_gains_db_, sizeof(current_gains_db_));
  std::memcpy(current_qs_, target_qs_, sizeof(current_qs_));
  current_preamp_db_ = target_preamp_db_;
  current_balance_ = target_balance_;
}

float NeonBiquadEq::UsableFrequency(float hz) const {
  const float max_hz = static_cast<float>(sample_rate_) * kMaxFreqFraction;
  return std::clamp(hz, kMinHz, max_hz);
}

bool NeonBiquadEq::IsFlat() const {
  if (std::abs(target_balance_) >= kSettledBalance) return false;
  if (std::abs(target_preamp_db_) >= kSettledDb) return false;
  for (int i = 0; i < kEqSlots; ++i) {
    if (std::abs(target_gains_db_[i]) >= kSettledDb) return false;
  }
  return true;
}

bool NeonBiquadEq::IsSettled() const {
  if (std::abs(current_balance_ - target_balance_) >= kSettledBalance) return false;
  if (std::abs(current_preamp_db_ - target_preamp_db_) >= kSettledDb) return false;
  for (int i = 0; i < kEqSlots; ++i) {
    if (std::abs(current_gains_db_[i] - target_gains_db_[i]) >= kSettledDb) return false;
  }
  return true;
}

void NeonBiquadEq::GlideTowards() {
  for (int i = 0; i < kEqSlots; ++i) {
    current_gains_db_[i] += (target_gains_db_[i] - current_gains_db_[i]) * kGlideRate;
    const float from_q = std::log(std::max(current_qs_[i], kMinQ));
    const float to_q = std::log(std::max(target_qs_[i], kMinQ));
    current_qs_[i] = std::exp(from_q + (to_q - from_q) * kGlideRate);
  }
  current_preamp_db_ += (target_preamp_db_ - current_preamp_db_) * kGlideRate;
  current_balance_ += (target_balance_ - current_balance_) * kGlideRate;
}

int NeonBiquadEq::PrepareSections() {
  int active = 0;
  for (int slot = 0; slot < kEqSlots; ++slot) {
    if (std::abs(current_gains_db_[slot]) >= kSettledDb) {
      UpdateCoefficients(slot);
      active_slots_[active++] = slot;
      running_[slot] = true;
    } else if (running_[slot]) {
      // Clear state for this slot across all channels
      for (int c = 0; c < channel_count_; ++c) {
        const size_t i = static_cast<size_t>((c * kEqSlots + slot) * 2);
        state_[i] = 0.0f;
        state_[i + 1] = 0.0f;
      }
      running_[slot] = false;
    }
  }
  return active;
}

void NeonBiquadEq::UpdateCoefficients(int slot) {
  if (sample_rate_ <= 0) return;
  const auto& spec = kEqLayoutSlots[slot];
  const float a = std::pow(10.0f, current_gains_db_[slot] / 40.0f);
  const float q = std::max(current_qs_[slot], kMinQ);
  const float base = precomputed_base_tan_[slot];

  float g = 0.0f;
  float k = 0.0f;
  switch (spec.kind) {
    case FilterKind::BELL:
      g = base;
      k = 1.0f / (q * a);
      mix_in_[slot] = 1.0f;
      mix_band_[slot] = k * (a * a - 1.0f);
      mix_low_[slot] = 0.0f;
      break;
    case FilterKind::LOW_SHELF:
      g = base / std::sqrt(a);
      k = 1.0f / q;
      mix_in_[slot] = 1.0f;
      mix_band_[slot] = k * (a - 1.0f);
      mix_low_[slot] = a * a - 1.0f;
      break;
    case FilterKind::HIGH_SHELF:
      g = base * std::sqrt(a);
      k = 1.0f / q;
      mix_in_[slot] = a * a;
      mix_band_[slot] = k * (1.0f - a) * a;
      mix_low_[slot] = 1.0f - a * a;
      break;
  }
  const float d = 1.0f / (1.0f + g * (g + k));
  coeff_a1_[slot] = d;
  coeff_a2_[slot] = g * d;
  coeff_a3_[slot] = g * (g * d);
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

  if (IsFlat() && IsSettled()) {
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

#if defined(__ARM_NEON) || defined(__aarch64__)
    // High-performance vectorized stereo path (channel_count == 2)
    if (channel_count_ == 2) {
      const float gain_l = channel_gain_[0];
      const float gain_r = channel_gain_[1];
      float* block_ptr = samples + (frame_offset * 2);

      // Load state pointers for active slots
      for (int f = 0; f < sub_block; ++f) {
        float l = block_ptr[f * 2];
        float r = block_ptr[f * 2 + 1];

        // Process active cascade bands
        for (int idx = 0; idx < active_count; ++idx) {
          const int slot = active_slots_[idx];
          const float a1 = coeff_a1_[slot];
          const float a2 = coeff_a2_[slot];
          const float a3 = coeff_a3_[slot];
          const float m_in = mix_in_[slot];
          const float m_band = mix_band_[slot];
          const float m_low = mix_low_[slot];

          // Left channel
          const size_t i_l = static_cast<size_t>(slot * 2);
          const float ic1_l = state_[i_l];
          const float ic2_l = state_[i_l + 1];
          const float v3_l = l - ic2_l;
          const float v1_l = a1 * ic1_l + a2 * v3_l;
          const float v2_l = ic2_l + a2 * ic1_l + a3 * v3_l;
          state_[i_l] = 2.0f * v1_l - ic1_l;
          state_[i_l + 1] = 2.0f * v2_l - ic2_l;
          l = m_in * l + m_band * v1_l + m_low * v2_l;

          // Right channel
          const size_t i_r = static_cast<size_t>((kEqSlots + slot) * 2);
          const float ic1_r = state_[i_r];
          const float ic2_r = state_[i_r + 1];
          const float v3_r = r - ic2_r;
          const float v1_r = a1 * ic1_r + a2 * v3_r;
          const float v2_r = ic2_r + a2 * ic1_r + a3 * v3_r;
          state_[i_r] = 2.0f * v1_r - ic1_r;
          state_[i_r + 1] = 2.0f * v2_r - ic2_r;
          r = m_in * r + m_band * v1_r + m_low * v2_r;
        }

        block_ptr[f * 2] = l * gain_l;
        block_ptr[f * 2 + 1] = r * gain_r;
      }
    } else if (channel_count_ == 4) {
      // 4-channel SIMD path using vfmaq_f32 across all 4 channels simultaneously
      const float32x4_t v_gains = vld1q_f32(channel_gain_.data());
      float* block_ptr = samples + (frame_offset * 4);

      for (int f = 0; f < sub_block; ++f) {
        float32x4_t x = vld1q_f32(block_ptr + f * 4);

        for (int idx = 0; idx < active_count; ++idx) {
          const int slot = active_slots_[idx];
          const float32x4_t a1 = vdupq_n_f32(coeff_a1_[slot]);
          const float32x4_t a2 = vdupq_n_f32(coeff_a2_[slot]);
          const float32x4_t a3 = vdupq_n_f32(coeff_a3_[slot]);
          const float32x4_t m_in = vdupq_n_f32(mix_in_[slot]);
          const float32x4_t m_band = vdupq_n_f32(mix_band_[slot]);
          const float32x4_t m_low = vdupq_n_f32(mix_low_[slot]);

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

          // State updates: ic_new = 2 * v - ic
          ic1 = vfmaq_n_f32(vnegq_f32(ic1), v1, 2.0f);
          ic2 = vfmaq_n_f32(vnegq_f32(ic2), v2, 2.0f);

          // Scatter updated state back
          state_[slot * 2] = vgetq_lane_f32(ic1, 0);
          state_[(kEqSlots + slot) * 2] = vgetq_lane_f32(ic1, 1);
          state_[(2 * kEqSlots + slot) * 2] = vgetq_lane_f32(ic1, 2);
          state_[(3 * kEqSlots + slot) * 2] = vgetq_lane_f32(ic1, 3);

          state_[slot * 2 + 1] = vgetq_lane_f32(ic2, 0);
          state_[(kEqSlots + slot) * 2 + 1] = vgetq_lane_f32(ic2, 1);
          state_[(2 * kEqSlots + slot) * 2 + 1] = vgetq_lane_f32(ic2, 2);
          state_[(3 * kEqSlots + slot) * 2 + 1] = vgetq_lane_f32(ic2, 3);

          // Output calculation
          float32x4_t y = vmulq_f32(m_in, x);
          y = vfmaq_f32(y, m_band, v1);
          x = vfmaq_f32(y, m_low, v2);
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
          float sample = samples[base_idx + ch];
          for (int idx = 0; idx < active_count; ++idx) {
            const int slot = active_slots_[idx];
            const size_t i = static_cast<size_t>((ch * kEqSlots + slot) * 2);
            const float ic1 = state_[i];
            const float ic2 = state_[i + 1];
            const float v3 = sample - ic2;
            const float v1 = coeff_a1_[slot] * ic1 + coeff_a2_[slot] * v3;
            const float v2 = ic2 + coeff_a2_[slot] * ic1 + coeff_a3_[slot] * v3;
            state_[i] = 2.0f * v1 - ic1;
            state_[i + 1] = 2.0f * v2 - ic2;
            sample = mix_in_[slot] * sample + mix_band_[slot] * v1 + mix_low_[slot] * v2;
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