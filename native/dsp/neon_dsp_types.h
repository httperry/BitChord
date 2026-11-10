#pragma once

#include <cstddef>
#include <cstdint>
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace bitchord::dsp {

// ARM64 Floating-point control register manipulation for flush-to-zero (FTZ)
// and default NaN mode to eliminate software denormal flushes.
inline void EnableHardwareFlushToZero() {
#if defined(__aarch64__)
  uint64_t fpcr;
  asm volatile("mrs %0, fpcr" : "=r"(fpcr));
  // Bit 24: FZ (Flush to zero)
  // Bit 25: DN (Default NaN)
  fpcr |= (1ULL << 24) | (1ULL << 25);
  asm volatile("msr fpcr, %0" : : "r"(fpcr));
#endif
}

constexpr int kMaxChannels = 8;
constexpr int kEqSlots = 10;
constexpr int kGlideFrames = 64;
constexpr float kGlideRate = 0.08f;
constexpr float kSettledDb = 0.01f;
constexpr float kSettledBalance = 0.0005f;
constexpr float kMinQ = 0.05f;
constexpr float kMinHz = 10.0f;
constexpr float kMaxFreqFraction = 0.45f;
constexpr float kDenormalFloor = 1e-12f;
constexpr float kPi = 3.14159265358979323846f;

enum class FilterKind : int32_t {
  BELL = 0,
  LOW_SHELF = 1,
  HIGH_SHELF = 2
};

struct FilterSlotSpec {
  FilterKind kind;
  float frequencyHz;
};

// Fixed EqLayout specifications matching EqualizerCurve.kt
static const FilterSlotSpec kEqLayoutSlots[kEqSlots] = {
    {FilterKind::LOW_SHELF, 60.0f},
    {FilterKind::BELL, 150.0f},
    {FilterKind::BELL, 400.0f},
    {FilterKind::BELL, 1000.0f},
    {FilterKind::BELL, 2500.0f},
    {FilterKind::BELL, 6000.0f},
    {FilterKind::HIGH_SHELF, 14000.0f},
    {FilterKind::LOW_SHELF, 250.0f},
    {FilterKind::BELL, 1000.0f},
    {FilterKind::HIGH_SHELF, 4000.0f}
};

}  // namespace bitchord::dsp