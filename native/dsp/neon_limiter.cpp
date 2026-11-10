#include "neon_limiter.h"
#include <algorithm>
#include <cmath>

namespace bitchord::dsp {

NeonLimiter::NeonLimiter() = default;

void NeonLimiter::Configure(int sample_rate, int channel_count) {
  sample_rate_ = sample_rate;
  channel_count_ = channel_count;

  if (sample_rate <= 0) {
    glide_coef_ = 1.0f;
    release_coef_ = 1.0f;
  } else {
    glide_coef_ = static_cast<float>(1.0 - std::exp(-1.0 / (kGlideSeconds * sample_rate)));
    release_coef_ = static_cast<float>(1.0 - std::exp(-1.0 / (kReleaseSeconds * sample_rate)));
  }
  Reset();
}

void NeonLimiter::Reset() {
  current_gain_ = target_gain_;
  current_trim_ = target_trim_;
  reduction_ = 1.0f;
}

void NeonLimiter::SetParameters(float target_gain, float target_trim, bool hold_limiter, bool snap) {
  target_gain_ = target_gain;
  target_trim_ = target_trim;
  hold_limiter_ = hold_limiter;
  if (snap) {
    current_gain_ = target_gain;
    current_trim_ = target_trim;
  }
}

bool NeonLimiter::IsParked() const {
  return (!hold_limiter_ && target_gain_ == 1.0f) && target_trim_ >= 1.0f &&
         std::abs(current_gain_ - 1.0f) < kSettled && current_trim_ >= 1.0f - kSettled &&
         reduction_ >= kParkReduction;
}

void NeonLimiter::Process(float* samples, int frame_count) {
  if (frame_count <= 0 || channel_count_ < 1 || sample_rate_ <= 0) return;

  if (IsParked()) {
    current_gain_ = 1.0f;
    current_trim_ = 1.0f;
    reduction_ = 1.0f;
    return;
  }

  EnableHardwareFlushToZero();

  float gain = current_gain_;
  float trim = current_trim_;
  float gr = reduction_;
  const float eff_target_gain = target_gain_;
  const float eff_target_trim = target_trim_;
  const float g_coef = glide_coef_;
  const float r_coef = release_coef_;
  const int channels = channel_count_;

#if defined(__ARM_NEON) || defined(__aarch64__)
  if (channels == 2) {
    int f = 0;
    const int vector_end = frame_count - 3;

    for (; f < vector_end; f += 4) {
      // 1. Vectorized Peak Detection for 4 frames
      float32x4x2_t lr = vld2q_f32(samples + f * 2);
      float32x4_t abs_l = vabsq_f32(lr.val[0]);
      float32x4_t abs_r = vabsq_f32(lr.val[1]);
      float32x4_t peak4 = vmaxq_f32(abs_l, abs_r);

      // Check if fast-path scalar gain can be applied
      alignas(16) float peaks[4];
      vst1q_f32(peaks, peak4);

      alignas(16) float applied_gains[4];
      for (int k = 0; k < 4; ++k) {
        gain += (eff_target_gain - gain) * g_coef;
        trim += (eff_target_trim - trim) * g_coef;
        const float scale = gain * trim;
        const float peak = peaks[k] * scale;
        const float wanted = (peak > kLimit) ? (kLimit / peak) : 1.0f;
        gr = (wanted < gr) ? wanted : gr + (wanted - gr) * r_coef;
        applied_gains[k] = scale * gr;
      }

      // 2. Vectorized In-Place Sample Scaling
      float32x4_t v_applied = vld1q_f32(applied_gains);
      lr.val[0] = vmulq_f32(lr.val[0], v_applied);
      lr.val[1] = vmulq_f32(lr.val[1], v_applied);

      vst2q_f32(samples + f * 2, lr);
    }

    // Remainder frames
    for (; f < frame_count; ++f) {
      gain += (eff_target_gain - gain) * g_coef;
      trim += (eff_target_trim - trim) * g_coef;
      const float scale = gain * trim;

      const float s_l = samples[f * 2];
      const float s_r = samples[f * 2 + 1];
      const float peak = std::max(std::abs(s_l), std::abs(s_r)) * scale;

      const float wanted = (peak > kLimit) ? (kLimit / peak) : 1.0f;
      gr = (wanted < gr) ? wanted : gr + (wanted - gr) * r_coef;
      const float applied = scale * gr;

      samples[f * 2] = s_l * applied;
      samples[f * 2 + 1] = s_r * applied;
    }
  } else {
#endif
    // Generic multichannel loop
    int sample_idx = 0;
    for (int f = 0; f < frame_count; ++f) {
      gain += (eff_target_gain - gain) * g_coef;
      trim += (eff_target_trim - trim) * g_coef;
      const float scale = gain * trim;

      float peak = 0.0f;
      for (int c = 0; c < channels; ++c) {
        const float mag = std::abs(samples[sample_idx + c]);
        if (mag > peak) peak = mag;
      }
      peak *= scale;

      const float wanted = (peak > kLimit) ? (kLimit / peak) : 1.0f;
      gr = (wanted < gr) ? wanted : gr + (wanted - gr) * r_coef;
      const float applied = scale * gr;

      for (int c = 0; c < channels; ++c) {
        samples[sample_idx + c] *= applied;
      }
      sample_idx += channels;
    }
#if defined(__ARM_NEON) || defined(__aarch64__)
  }
#endif

  current_gain_ = gain;
  current_trim_ = trim;
  reduction_ = gr;
}

}  // namespace bitchord::dsp