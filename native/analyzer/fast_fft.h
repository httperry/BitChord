#pragma once

#include <algorithm>
#include <cmath>
#include <complex>
#include <vector>

namespace bitchord::smart {

namespace {
constexpr double kFastFftPi = 3.14159265358979323846;
}

/**
 * High-performance, vector-friendly radix-2 in-place FFT with cached twiddle factors
 * and bit-reversal lookup tables. Eliminates transcendental runtime function calls
 * and successive complex multiplication drift while remaining bit-accurate.
 */
class FastFft {
 public:
  static void Transform(std::vector<std::complex<double>>& values) {
    const size_t n = values.size();
    if (n <= 1) return;

    thread_local FastFft instance;
    instance.ensure(n);
    instance.execute(values);
  }

 private:
  size_t current_size_ = 0;
  std::vector<size_t> bit_rev_;
  std::vector<std::complex<double>> twiddles_;

  void ensure(size_t n) {
    if (current_size_ == n) return;
    current_size_ = n;
    bit_rev_.resize(n);
    for (size_t index = 0; index < n; ++index) {
      size_t rev = 0;
      size_t temp = index;
      for (size_t bit = 1; bit < n; bit <<= 1) {
        rev = (rev << 1) | (temp & 1);
        temp >>= 1;
      }
      bit_rev_[index] = rev;
    }

    twiddles_.resize(n / 2);
    for (size_t i = 0; i < n / 2; ++i) {
      const double angle = -2.0 * kFastFftPi * static_cast<double>(i) / static_cast<double>(n);
      twiddles_[i] = std::complex<double>(std::cos(angle), std::sin(angle));
    }
  }

  void execute(std::vector<std::complex<double>>& values) const {
    const size_t n = current_size_;
    for (size_t i = 0; i < n; ++i) {
      const size_t j = bit_rev_[i];
      if (i < j) std::swap(values[i], values[j]);
    }

    double* data = reinterpret_cast<double*>(values.data());

    for (size_t length = 2; length <= n; length <<= 1) {
      const size_t half = length >> 1;
      const size_t step = n / length;
      for (size_t start = 0; start < n; start += length) {
        const size_t base_u = start * 2;
        const size_t base_v = (start + half) * 2;
        for (size_t offset = 0; offset < half; ++offset) {
          const auto w = twiddles_[offset * step];
          const size_t idx_u = base_u + offset * 2;
          const size_t idx_v = base_v + offset * 2;

          const double vr = data[idx_v];
          const double vi = data[idx_v + 1];
          const double wr = w.real();
          const double wi = w.imag();

          const double or_ = vr * wr - vi * wi;
          const double oi_ = vr * wi + vi * wr;

          const double ur = data[idx_u];
          const double ui = data[idx_u + 1];

          data[idx_u] = ur + or_;
          data[idx_u + 1] = ui + oi_;
          data[idx_v] = ur - or_;
          data[idx_v + 1] = ui - oi_;
        }
      }
    }
  }
};

inline void FastFftTransform(std::vector<std::complex<double>>& values) {
  FastFft::Transform(values);
}

}  // namespace bitchord::smart
