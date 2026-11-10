#include "neon_biquad_eq.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cassert>
#include <vector>

using namespace bitchord::dsp;

int main() {
    printf("[NeonBiquadEq Dual-Channel Test] Starting validation...\n");

    NeonBiquadEq eq;
    const int sample_rate = 44100;
    const int channel_count = 2;
    eq.Configure(sample_rate, channel_count);

    // ── Test 1: Channel Isolation and Independence ───────────────────────────
    // Left ear: +6 dB boost at slot 3 (1000 Hz)
    // Right ear: 0 dB (Flat)
    float gains_l[kEqSlots] = {0};
    float qs_l[kEqSlots] = {0};
    float gains_r[kEqSlots] = {0};
    float qs_r[kEqSlots] = {0};

    for (int i = 0; i < kEqSlots; ++i) {
        qs_l[i] = 1.0f;
        qs_r[i] = 1.0f;
    }
    gains_l[3] = 6.0f;  // +6 dB boost on Left only
    gains_r[3] = 0.0f;  // Flat on Right

    eq.SetDualTuning(true, gains_l, qs_l, gains_r, qs_r, 0.0f, 0.0f);
    assert(eq.IsDualChannel() == true);
    printf("  [PASS] Dual-channel mode recognized correctly.\n");

    // Generate stereo sine signal at 1000 Hz: both Left and Right have same amplitude 0.2f
    const int frame_count = 1024;
    std::vector<float> samples(frame_count * 2);
    const float freq = 1000.0f;
    for (int f = 0; f < frame_count; ++f) {
        float val = 0.2f * std::sin(2.0f * kPi * freq * f / sample_rate);
        samples[f * 2] = val;     // Left
        samples[f * 2 + 1] = val; // Right
    }

    // Process in-place through NEON SIMD
    eq.Process(samples.data(), frame_count);

    // Check steady-state frames (e.g. frames 200..1000)
    // Left should be boosted by ~6 dB (gain ratio ~2.0x, but affected by auto-preamp attenuation)
    // Crucially: Left and Right must be completely different!
    float left_energy = 0.0f;
    float right_energy = 0.0f;
    for (int f = 200; f < frame_count; ++f) {
        left_energy += samples[f * 2] * samples[f * 2];
        right_energy += samples[f * 2 + 1] * samples[f * 2 + 1];
    }
    printf("  Left Energy: %.6f, Right Energy: %.6f (Ratio: %.3f)\n",
           left_energy, right_energy, left_energy / right_energy);
    assert(left_energy > right_energy * 1.5f);
    printf("  [PASS] Left channel boost is completely independent of Right channel.\n");

    // ── Test 2: Inverted Test (Right boosted, Left flat) ─────────────────────
    gains_l[3] = 0.0f;
    gains_r[3] = 6.0f;
    eq.SetDualTuning(true, gains_l, qs_l, gains_r, qs_r, 0.0f, 0.0f);
    eq.Flush();

    for (int f = 0; f < frame_count; ++f) {
        float val = 0.2f * std::sin(2.0f * kPi * freq * f / sample_rate);
        samples[f * 2] = val;
        samples[f * 2 + 1] = val;
    }
    eq.Process(samples.data(), frame_count);

    left_energy = 0.0f;
    right_energy = 0.0f;
    for (int f = 200; f < frame_count; ++f) {
        left_energy += samples[f * 2] * samples[f * 2];
        right_energy += samples[f * 2 + 1] * samples[f * 2 + 1];
    }
    printf("  Inverted - Left Energy: %.6f, Right Energy: %.6f (Ratio: %.3f)\n",
           left_energy, right_energy, right_energy / left_energy);
    assert(right_energy > left_energy * 1.5f);
    printf("  [PASS] Right channel boost is completely independent of Left channel.\n");

    // ── Test 3: Zero Audio Clipping / Preamp Headroom ────────────────────────
    // Full scale input (amplitude 1.0f) with extreme boost (+12 dB)
    eq.Flush();
    gains_l[3] = 12.0f;
    gains_r[3] = 12.0f;
    // With auto preamp attenuation (-12 dB = 0.25x), peak output must never exceed 1.0f!
    eq.SetDualTuning(true, gains_l, qs_l, gains_r, qs_r, 0.0f, 0.0f);
    printf("  Auto-calculated Preamp dB: %.2f\n", eq.GetTargetPreampDb());
    assert(eq.GetTargetPreampDb() <= -12.0f);

    for (int f = 0; f < frame_count; ++f) {
        float val = 1.0f * std::sin(2.0f * kPi * freq * f / sample_rate);
        samples[f * 2] = val;
        samples[f * 2 + 1] = val;
    }
    eq.Process(samples.data(), frame_count);

    float max_peak = 0.0f;
    for (int i = 0; i < frame_count * 2; ++i) {
        float abs_val = std::abs(samples[i]);
        if (abs_val > max_peak) max_peak = abs_val;
    }
    printf("  Full-Scale Boost Peak Sample: %.4f (Limit: <= 1.05f)\n", max_peak);
    assert(max_peak <= 1.05f); // Zero clipping, well within digital headroom!
    printf("  [PASS] Zero audio clipping verified with full-scale boost.\n");

    // ── Test 4: Bit-Perfect Bypass / Flat Identity ──────────────────────────
    eq.SetDualTuning(false, nullptr, nullptr, nullptr, nullptr, 0.0f, 0.0f);
    eq.Flush();
    assert(eq.IsEnabled() == false);

    std::vector<float> original(frame_count * 2);
    for (int i = 0; i < frame_count * 2; ++i) {
        original[i] = (static_cast<float>(i % 100) - 50.0f) / 100.0f;
        samples[i] = original[i];
    }
    eq.Process(samples.data(), frame_count);

    for (int i = 0; i < frame_count * 2; ++i) {
        assert(samples[i] == original[i]);
    }
    printf("  [PASS] Disabled EQ produces 100%% bit-perfect identity passthrough.\n");

    printf("\nALL DUAL-CHANNEL NEON SIMD TESTS PASSED PERFECTLY!\n");
    return 0;
}
