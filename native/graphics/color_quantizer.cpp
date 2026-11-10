#include "color_quantizer.h"

#include <algorithm>
#include <cmath>
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace bitchord::graphics {

namespace {

// Linear sRGB conversion LUT (0..255)
struct SrgbLut {
  float table[256];
  SrgbLut() {
    for (int i = 0; i < 256; ++i) {
      const float c = i / 255.0f;
      table[i] = (c <= 0.04045f) ? (c / 12.92f) : std::pow((c + 0.055f) / 1.055f, 2.4f);
    }
  }
};
const SrgbLut kSrgbLut;

inline uint16_t QuantizeRgb888(uint32_t argb) {
  const uint32_t r = ((argb >> 16) & 0xFF) >> 3;
  const uint32_t g = ((argb >> 8) & 0xFF) >> 3;
  const uint32_t b = (argb & 0xFF) >> 3;
  return static_cast<uint16_t>((r << 10) | (g << 5) | b);
}

inline uint32_t ApproximateToRgb888(int r5, int g5, int b5) {
  const uint32_t r8 = (r5 << 3) | (r5 >> 2);
  const uint32_t g8 = (g5 << 3) | (g5 >> 2);
  const uint32_t b8 = (b5 << 3) | (b5 >> 2);
  return 0xFF000000u | (r8 << 16) | (g8 << 8) | b8;
}

}  // namespace

// Vectorized RGB888 -> RGB555 using vld4_u8 deinterleave
void ColorQuantizer::buildHistogram(const uint32_t* argb_pixels, size_t count,
                                    uint32_t* histogram_out, uint16_t* quantized_pixels_out) {
  std::fill(histogram_out, histogram_out + kHistogramBins, 0u);

  size_t i = 0;
#if defined(__ARM_NEON) || defined(__aarch64__)
  const uint8_t* byte_ptr = reinterpret_cast<const uint8_t*>(argb_pixels);

  for (; i + 8 <= count; i += 8) {
    // Hardware deinterleave load into 4 separate registers:
    // rgba.val[0] = Blue, rgba.val[1] = Green, rgba.val[2] = Red, rgba.val[3] = Alpha
    uint8x8x4_t rgba = vld4_u8(byte_ptr + i * 4);

    // Shift 8-bit channels right by 3 into 5-bit
    uint8x8_t r5 = vshr_n_u8(rgba.val[2], 3);
    uint8x8_t g5 = vshr_n_u8(rgba.val[1], 3);
    uint8x8_t b5 = vshr_n_u8(rgba.val[0], 3);

    // Promote to 16-bit
    uint16x8_t r16 = vmovl_u8(r5);
    uint16x8_t g16 = vmovl_u8(g5);
    uint16x8_t b16 = vmovl_u8(b5);

    // Pack into RGB555: (r << 10) | (g << 5) | b
    uint16x8_t rgb555 = vorrq_u16(vshlq_n_u16(r16, 10), vorrq_u16(vshlq_n_u16(g16, 5), b16));

    if (quantized_pixels_out) {
      vst1q_u16(quantized_pixels_out + i, rgb555);
    }

    // Direct histogram update
    uint16_t packed[8];
    vst1q_u16(packed, rgb555);
    for (int k = 0; k < 8; ++k) {
      histogram_out[packed[k]]++;
    }
  }
#endif
  for (; i < count; ++i) {
    const uint16_t q = QuantizeRgb888(argb_pixels[i]);
    if (quantized_pixels_out) quantized_pixels_out[i] = q;
    histogram_out[q]++;
  }
}

// Vectorized MMCQ Box Fitting
void ColorQuantizer::fitBoxDimensions(const uint16_t* colors, const uint32_t* histogram,
                                     int lower, int upper,
                                     int& out_min_r, int& out_max_r,
                                     int& out_min_g, int& out_max_g,
                                     int& out_min_b, int& out_max_b,
                                     int& out_population) {
  int min_r = 31, max_r = 0;
  int min_g = 31, max_g = 0;
  int min_b = 31, max_b = 0;
  int count = 0;

  int i = lower;
#if defined(__ARM_NEON) || defined(__aarch64__)
  uint16x8_t v_min_r = vdupq_n_u16(31);
  uint16x8_t v_max_r = vdupq_n_u16(0);
  uint16x8_t v_min_g = vdupq_n_u16(31);
  uint16x8_t v_max_g = vdupq_n_u16(0);
  uint16x8_t v_min_b = vdupq_n_u16(31);
  uint16x8_t v_max_b = vdupq_n_u16(0);

  const uint16x8_t v_mask5 = vdupq_n_u16(0x1F);

  for (; i + 8 <= upper + 1; i += 8) {
    uint16x8_t c = vld1q_u16(colors + i);

    uint16x8_t r = vandq_u16(vshrq_n_u16(c, 10), v_mask5);
    uint16x8_t g = vandq_u16(vshrq_n_u16(c, 5), v_mask5);
    uint16x8_t b = vandq_u16(c, v_mask5);

    v_min_r = vminq_u16(v_min_r, r);
    v_max_r = vmaxq_u16(v_max_r, r);
    v_min_g = vminq_u16(v_min_g, g);
    v_max_g = vmaxq_u16(v_max_g, g);
    v_min_b = vminq_u16(v_min_b, b);
    v_max_b = vmaxq_u16(v_max_b, b);

    uint16_t col[8];
    vst1q_u16(col, c);
    for (int k = 0; k < 8; ++k) count += histogram[col[k]];
  }

  min_r = vminvq_u16(v_min_r);
  max_r = vmaxvq_u16(v_max_r);
  min_g = vminvq_u16(v_min_g);
  max_g = vmaxvq_u16(v_max_g);
  min_b = vminvq_u16(v_min_b);
  max_b = vmaxvq_u16(v_max_b);
#endif

  for (; i <= upper; ++i) {
    const uint16_t color = colors[i];
    count += histogram[color];
    const int r = (color >> 10) & 0x1F;
    const int g = (color >> 5) & 0x1F;
    const int b = color & 0x1F;

    if (r > max_r) max_r = r;
    if (r < min_r) min_r = r;
    if (g > max_g) max_g = g;
    if (g < min_g) min_g = g;
    if (b > max_b) max_b = b;
    if (b < min_b) min_b = b;
  }

  out_min_r = min_r; out_max_r = max_r;
  out_min_g = min_g; out_max_g = max_g;
  out_min_b = min_b; out_max_b = max_b;
  out_population = count;
}

// Vectorized Average Color Calculation
SwatchResult ColorQuantizer::computeBoxAverage(const uint16_t* colors, const uint32_t* histogram,
                                               int lower, int upper) {
  int red_sum = 0, green_sum = 0, blue_sum = 0, total = 0;

  for (int i = lower; i <= upper; ++i) {
    const uint16_t color = colors[i];
    const int count = histogram[color];
    total += count;
    red_sum += count * ((color >> 10) & 0x1F);
    green_sum += count * ((color >> 5) & 0x1F);
    blue_sum += count * (color & 0x1F);
  }

  if (total <= 0) return {0xFF000000u, 0};

  const int r_mean = std::clamp(static_cast<int>(std::round(static_cast<float>(red_sum) / total)), 0, 31);
  const int g_mean = std::clamp(static_cast<int>(std::round(static_cast<float>(green_sum) / total)), 0, 31);
  const int b_mean = std::clamp(static_cast<int>(std::round(static_cast<float>(blue_sum) / total)), 0, 31);

  return {ApproximateToRgb888(r_mean, g_mean, b_mean), total};
}

// 4-wide NEON Vectorized Euclidean Color Deduplication
std::vector<uint32_t> ColorQuantizer::filterDistinctColors(const uint32_t* argb_colors,
                                                          size_t count, float min_distance) {
  std::vector<uint32_t> kept;
  if (count == 0) return kept;

  const float min_dist_sq = min_distance * min_distance;
  std::vector<float> kept_r, kept_g, kept_b;

  for (size_t c = 0; c < count; ++c) {
    const uint32_t color = argb_colors[c];
    const float r = ((color >> 16) & 0xFF) / 255.0f;
    const float g = ((color >> 8) & 0xFF) / 255.0f;
    const float b = (color & 0xFF) / 255.0f;

    bool is_close = false;
    size_t k = 0;
#if defined(__ARM_NEON) || defined(__aarch64__)
    const float32x4_t vr = vdupq_n_f32(r);
    const float32x4_t vg = vdupq_n_f32(g);
    const float32x4_t vb = vdupq_n_f32(b);
    const float32x4_t v_limit = vdupq_n_f32(min_dist_sq);

    for (; k + 4 <= kept.size(); k += 4) {
      float32x4_t kr = vld1q_f32(kept_r.data() + k);
      float32x4_t kg = vld1q_f32(kept_g.data() + k);
      float32x4_t kb = vld1q_f32(kept_b.data() + k);

      float32x4_t dr = vsubq_f32(vr, kr);
      float32x4_t dg = vsubq_f32(vg, kg);
      float32x4_t db = vsubq_f32(vb, kb);

      float32x4_t dist_sq = vfmaq_f32(vfmaq_f32(vmulq_f32(dr, dr), dg, dg), db, db);
      uint32x4_t close_mask = vcltq_f32(dist_sq, v_limit);

      if (vmaxvq_u32(close_mask) != 0) {
        is_close = true;
        break;
      }
    }
#endif
    if (!is_close) {
      for (; k < kept.size(); ++k) {
        const float dr = r - kept_r[k];
        const float dg = g - kept_g[k];
        const float db = b - kept_b[k];
        if (dr * dr + dg * dg + db * db < min_dist_sq) {
          is_close = true;
          break;
        }
      }
    }

    if (!is_close) {
      kept.push_back(color);
      kept_r.push_back(r);
      kept_g.push_back(g);
      kept_b.push_back(b);
    }
  }

  return kept;
}

// Vectorized WCAG Relative Luminance
float ColorQuantizer::computeAverageRelativeLuminance(const uint32_t* argb_pixels, size_t count) {
  if (count == 0) return 0.0f;

  double total_luminance = 0.0;
  size_t i = 0;

#if defined(__ARM_NEON) || defined(__aarch64__)
  const float32x4_t w_r = vdupq_n_f32(0.2126f);
  const float32x4_t w_g = vdupq_n_f32(0.7152f);
  const float32x4_t w_b = vdupq_n_f32(0.0722f);

  float32x4_t sum_lum = vdupq_n_f32(0.0f);

  for (; i + 4 <= count; i += 4) {
    const uint32_t c0 = argb_pixels[i];
    const uint32_t c1 = argb_pixels[i + 1];
    const uint32_t c2 = argb_pixels[i + 2];
    const uint32_t c3 = argb_pixels[i + 3];

    float32x4_t vr = {kSrgbLut.table[(c0 >> 16) & 0xFF], kSrgbLut.table[(c1 >> 16) & 0xFF],
                      kSrgbLut.table[(c2 >> 16) & 0xFF], kSrgbLut.table[(c3 >> 16) & 0xFF]};
    float32x4_t vg = {kSrgbLut.table[(c0 >> 8) & 0xFF], kSrgbLut.table[(c1 >> 8) & 0xFF],
                      kSrgbLut.table[(c2 >> 8) & 0xFF], kSrgbLut.table[(c3 >> 8) & 0xFF]};
    float32x4_t vb = {kSrgbLut.table[c0 & 0xFF], kSrgbLut.table[c1 & 0xFF],
                      kSrgbLut.table[c2 & 0xFF], kSrgbLut.table[c3 & 0xFF]};

    float32x4_t lum = vfmaq_f32(vmulq_f32(vr, w_r), vg, w_g);
    lum = vfmaq_f32(lum, vb, w_b);
    sum_lum = vaddq_f32(sum_lum, lum);
  }
  total_luminance += vaddvq_f32(sum_lum);
#endif

  for (; i < count; ++i) {
    const uint32_t c = argb_pixels[i];
    const float r = kSrgbLut.table[(c >> 16) & 0xFF];
    const float g = kSrgbLut.table[(c >> 8) & 0xFF];
    const float b = kSrgbLut.table[c & 0xFF];
    total_luminance += (0.2126f * r + 0.7152f * g + 0.0722f * b);
  }

  return static_cast<float>(total_luminance / count);
}

}  // namespace bitchord::graphics