#pragma once

#include <cstdint>
#include <vector>

namespace bitchord::graphics {

struct SwatchResult {
  uint32_t argb;
  int population;
};

class ColorQuantizer {
 public:
  static constexpr int kWordWidth = 5;
  static constexpr int kHistogramBins = 1 << (kWordWidth * 3);  // 32,768

  // Vectorized RGB888 to RGB555 reduction and histogram population
  static void buildHistogram(const uint32_t* argb_pixels, size_t count,
                             uint32_t* histogram_out, uint16_t* quantized_pixels_out);

  // Vectorized MMCQ box fitting across color dimension boundaries
  static void fitBoxDimensions(const uint16_t* colors, const uint32_t* histogram,
                               int lower, int upper,
                               int& out_min_r, int& out_max_r,
                               int& out_min_g, int& out_max_g,
                               int& out_min_b, int& out_max_b,
                               int& out_population);

  // Vectorized box average color calculation
  static SwatchResult computeBoxAverage(const uint16_t* colors, const uint32_t* histogram,
                                        int lower, int upper);

  // Vectorized Euclidean color deduplication (distinctEnough)
  static std::vector<uint32_t> filterDistinctColors(const uint32_t* argb_colors,
                                                    size_t count, float min_distance);

  // Vectorized WCAG relative luminance across image band
  static float computeAverageRelativeLuminance(const uint32_t* argb_pixels, size_t count);
};

}  // namespace bitchord::graphics