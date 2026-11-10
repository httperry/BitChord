#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace bitchord::smart {

struct WaveformBands {
  std::vector<float> low;
  std::vector<float> mid;
  std::vector<float> high;
};

class WaveformDecimator {
 public:
  WaveformDecimator(int bins, double low_hz = 200.0, double high_hz = 2500.0);

  // Processes incoming stream PCM block into downsampled DJ waveform bins
  void processBlock(const float* samples, size_t count, double at_seconds,
                    double from_seconds, double to_seconds, int sample_rate);

  // Finalizes percentile normalization on all three bands
  WaveformBands finalize();

  // Vectorized standalone RMS envelope calculation for curve windows
  static void computeRmsEnvelope(const float* samples, size_t count,
                                 size_t window_size, float* out_rms, size_t out_count);

  // Vectorized min/max peak decimation for display buffers
  static void computeMinMaxDecimation(const float* samples, size_t count,
                                     float* out_min, float* out_max, size_t bins);

 private:
  void normalizeBand(std::vector<float>& band, float ceiling);

  int bins_;
  double low_hz_;
  double high_hz_;
  int configured_rate_ = 0;
  float low_a_ = 0.0f;
  float high_a_ = 0.0f;

  // Filter state
  float l1_ = 0.0f, l2_ = 0.0f;
  float h1_ = 0.0f, h2_ = 0.0f;

  std::vector<float> low_peaks_;
  std::vector<float> mid_peaks_;
  std::vector<float> high_peaks_;
};

}  // namespace bitchord::smart