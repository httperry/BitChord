#include "neon_spatializer.h"
#include <algorithm>
#include <cmath>

namespace bitchord::dsp {

NeonSpatializer::NeonSpatializer() = default;

void NeonSpatializer::Configure(int sample_rate, int channel_count) {
  sample_rate_ = sample_rate;
  channel_count_ = channel_count;

  if (channel_count != 2 || sample_rate <= 0) {
    Reset();
    return;
  }

  const int target_delay = std::max(1, static_cast<int>(std::round(sample_rate * kDelayMs / 1000.0f)));
  int p2 = 1;
  while (p2 < target_delay) p2 <<= 1;
  const int lower = p2 >> 1;
  const int delay_samples = (lower >= 1 && (target_delay - lower) < (p2 - target_delay)) ? lower : p2;

  delay_left_.assign(static_cast<size_t>(delay_samples), 0.0f);
  delay_right_.assign(static_cast<size_t>(delay_samples), 0.0f);
  delay_mask_ = delay_samples - 1;
  Flush();
}

void NeonSpatializer::Reset() {
  delay_left_.clear();
  delay_right_.clear();
  delay_index_ = 0;
  delay_mask_ = 0;
  lowpass_left_ = 0.0f;
  lowpass_right_ = 0.0f;
  channel_count_ = 0;
  sample_rate_ = 0;
}

void NeonSpatializer::Flush() {
  std::fill(delay_left_.begin(), delay_left_.end(), 0.0f);
  std::fill(delay_right_.begin(), delay_right_.end(), 0.0f);
  delay_index_ = 0;
  lowpass_left_ = 0.0f;
  lowpass_right_ = 0.0f;
}

void NeonSpatializer::Process(float* samples, int frame_count) {
  if (!enabled_ || frame_count <= 0 || channel_count_ != 2 || delay_mask_ <= 0) return;

  EnableHardwareFlushToZero();

  int d_idx = delay_index_;
  const int mask = delay_mask_;
  float lp_l = lowpass_left_;
  float lp_r = lowpass_right_;

  float* delay_l = delay_left_.data();
  float* delay_r = delay_right_.data();

#if defined(__ARM_NEON) || defined(__aarch64__)
  // Matrix constants:
  // widenedLeft  = 0.5 * (1 + widthGain) * L + 0.5 * (1 - widthGain) * R
  // widenedRight = 0.5 * (1 - widthGain) * L + 0.5 * (1 + widthGain) * R
  constexpr float c_same = 0.5f * (1.0f + kWidthGain);  // 1.75f
  constexpr float c_diff = 0.5f * (1.0f - kWidthGain);  // -0.75f

  int f = 0;
  const int vector_end = frame_count - 3;

  for (; f < vector_end; f += 4) {
    // 1. Load 4 interleaved stereo frames: [L0, R0, L1, R1, L2, R2, L3, R3]
    // De-interleave directly into Left and Right vectors
    float32x4x2_t in_lr = vld2q_f32(samples + f * 2);
    float32x4_t left = in_lr.val[0];
    float32x4_t right = in_lr.val[1];

    // 2. Vectorized Mid/Side Stereo Widening
    float32x4_t wid_l = vmulq_n_f32(left, c_same);
    wid_l = vfmaq_n_f32(wid_l, right, c_diff);

    float32x4_t wid_r = vmulq_n_f32(right, c_same);
    wid_r = vfmaq_n_f32(wid_r, left, c_diff);

    // 3. Circular Delay Reads & Single-Pole Filter Updates
    // Fetch delayed samples across 4 frames
    alignas(16) float delayed_r[4];
    alignas(16) float delayed_l[4];
    for (int k = 0; k < 4; ++k) {
      const int cur_idx = (d_idx + k) & mask;
      delayed_r[k] = delay_r[cur_idx];
      delayed_l[k] = delay_l[cur_idx];
    }

    // Single-pole lowpass recurrence in registers
    alignas(16) float lp_l_out[4];
    alignas(16) float lp_r_out[4];
    for (int k = 0; k < 4; ++k) {
      lp_l += kLowpassCoeff * (delayed_r[k] - lp_l);
      lp_r += kLowpassCoeff * (delayed_l[k] - lp_r);
      lp_l_out[k] = lp_l;
      lp_r_out[k] = lp_r;
    }

    // 4. Crossfeed Mixing and Makeup Gain via FMA
    wid_l = vfmaq_n_f32(wid_l, vld1q_f32(lp_l_out), kCrossfeedGain);
    wid_r = vfmaq_n_f32(wid_r, vld1q_f32(lp_r_out), kCrossfeedGain);

    float32x4x2_t out_lr;
    out_lr.val[0] = vmulq_n_f32(wid_l, kOutputGain);
    out_lr.val[1] = vmulq_n_f32(wid_r, kOutputGain);

    // 5. Store new samples into delay buffer
    alignas(16) float store_l[4];
    alignas(16) float store_r[4];
    vst1q_f32(store_l, left);
    vst1q_f32(store_r, right);
    for (int k = 0; k < 4; ++k) {
      const int cur_idx = (d_idx + k) & mask;
      delay_l[cur_idx] = store_l[k];
      delay_r[cur_idx] = store_r[k];
    }
    d_idx = (d_idx + 4) & mask;

    // 6. Interleave and write back to samples in-place
    vst2q_f32(samples + f * 2, out_lr);
  }

  // Scalar tail
  for (; f < frame_count; ++f) {
    const float left = samples[f * 2];
    const float right = samples[f * 2 + 1];

    const float mid = (left + right) * 0.5f;
    const float side = (left - right) * 0.5f * kWidthGain;
    float wid_left = mid + side;
    float wid_right = mid - side;

    const float del_r = delay_r[d_idx];
    const float del_l = delay_l[d_idx];
    lp_l += kLowpassCoeff * (del_r - lp_l);
    lp_r += kLowpassCoeff * (del_l - lp_r);

    wid_left += lp_l * kCrossfeedGain;
    wid_right += lp_r * kCrossfeedGain;

    delay_l[d_idx] = left;
    delay_r[d_idx] = right;
    d_idx = (d_idx + 1) & mask;

    samples[f * 2] = wid_left * kOutputGain;
    samples[f * 2 + 1] = wid_right * kOutputGain;
  }
#else
  for (int f = 0; f < frame_count; ++f) {
    const float left = samples[f * 2];
    const float right = samples[f * 2 + 1];

    const float mid = (left + right) * 0.5f;
    const float side = (left - right) * 0.5f * kWidthGain;
    float wid_left = mid + side;
    float wid_right = mid - side;

    const float del_r = delay_r[d_idx];
    const float del_l = delay_l[d_idx];
    lp_l += kLowpassCoeff * (del_r - lp_l);
    lp_r += kLowpassCoeff * (del_l - lp_r);

    wid_left += lp_l * kCrossfeedGain;
    wid_right += lp_r * kCrossfeedGain;

    delay_l[d_idx] = left;
    delay_r[d_idx] = right;
    d_idx = (d_idx + 1) & mask;

    samples[f * 2] = wid_left * kOutputGain;
    samples[f * 2 + 1] = wid_right * kOutputGain;
  }
#endif

  delay_index_ = d_idx;
  lowpass_left_ = lp_l;
  lowpass_right_ = lp_r;
}

}  // namespace bitchord::dsp