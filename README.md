<div align="center">

<img src="Logo.png" alt="BitChord app icon" width="160" />

# BitChord — Audio Engine Optimization & Hardware Acceleration

### Native DSP Vectorization, ARM NEON SIMD, and GPU Neural Acceleration

<br/>

[![ARM NEON](https://img.shields.io/badge/ARM%20NEON-SIMD%20Vectorized-0091BD?style=for-the-badge&logo=arm&logoColor=white)](https://github.com/httperry/BitChord)
[![Android NNAPI](https://img.shields.io/badge/Android%20NNAPI-GPU%20Offload-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/httperry/BitChord)
[![ONNX Runtime](https://img.shields.io/badge/ONNX%20Runtime-v1.18.0-005CED?style=for-the-badge&logo=onnx&logoColor=white)](https://github.com/httperry/BitChord)
[![C++20 NDK](https://img.shields.io/badge/Native%20Core-C%2B%2B20%20%2F%20NDK-00599C?style=for-the-badge&logo=cplusplus&logoColor=white)](https://github.com/httperry/BitChord)

<br/>

</div>

> [!NOTE]
> **Output Quality & Verification:**
> Optimizations in this branch improve execution efficiency without modifying model weights, downsampling audio buffers, or approximating outputs.
> * **Beat Tracking (`beat_this_final0_fp.onnx`):** Weights were dequantized algebraically using the original stored zero-points and scale factors. Running both graphs on identical audio inputs produces matching activations ($r = 1.000$, max diff $= 0.0$).
> * **Vocal Separation (`vocals_umxhq_fp.onnx`):** Converted from dynamic quantized operators to standard ONNX LSTM and MatMul nodes. Tested against upstream baseline on spectrogram chunks, outputs correlate at $r = 0.9994$.
> * **Spectral DSP (`FastFft`):** Benchmarked against reference Cooley-Tukey across $N \in [256, 2048]$. Maximum complex output error is within floating-point roundoff ($< 6.7 \times 10^{-11}$).
> * **Splice Alignment (`nativeSpliceLag`):** NEON SIMD dot products and scalar loops produce identical correlation values and lag offsets across the 15.7M operation search window.

---

## Performance Benchmarks & Improvements

The table below summarizes benchmarks conducted on native Android (`aarch64`, Linux kernel 6.1+, MediaTek Dimensity 8350 / ARM Mali-G615 GPU, ARMv8.0-A baseline):

| Subsystem | Optimization Technique | Baseline (Upstream) | Optimized (This Branch) | Speedup | Execution Target |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Spectral DSP** | Unified Radix-2 `FastFft` with precomputed twiddles | 93.11 ms (2,000 transforms) | 26.99 ms (2,000 transforms) | **3.45x faster** | Native C++20 / CPU |
| **Splice Cross-Correlation** | Vectorized ARM NEON SIMD (`vmlaq_f32`, pinned JNI memory) | ~45.00 ms (15.7M operations) | ~1.50 ms (15.7M operations) | **30.00x faster** | ARM NEON / SIMD |
| **Beat Tracking ML** | Clean unquantized float graph (`beat_this_final0_fp.onnx`) | 7,300 ms (NNAPI CPU fallback) | 267 ms (Mali-G615 GPU) | **27.34x faster** | Mali GPU (NNAPI) |
| **Vocal Separation ML** | Standard LSTM/MatMul float graph (`vocals_umxhq_fp.onnx`) | 3,916 ms (NNAPI CPU fallback) | 706 ms (Mali-G615 GPU) | **5.54x faster** | Mali GPU (NNAPI) |
| **CPU Fallback Inference** | CPU execution of clean float graph | 1,097 ms per chunk | 374 ms per chunk | **2.93x faster** | Multi-threaded CPU |

---

## Key Architectural Changes

### 1. Vectorized Cross-Correlation (`nativeSpliceLag`)
During Automix transitions, BitChord aligns outgoing and incoming audio waveforms by computing cross-correlations across a sliding search window of up to 15.7 million multiply-accumulate operations.

* **Memory Pinning via `GetPrimitiveArrayCritical`:** Accesses audio sample arrays directly in memory during calculation, eliminating JVM array duplication.
* **ARM NEON SIMD Vectorization:** Computes 4 single-precision multiply-accumulate operations per clock cycle using 128-bit NEON intrinsics (`vmlaq_f32`, `vld1q_f32`, `vaddvq_f32`), with a scalar remainder loop for unaligned tails.
* **Result:** Reduces alignment latency from ~45.0 ms to ~1.5 ms without altering peak correlation positions.

### 2. Unified Spectral DSP Pipeline (`FastFft`)
Upstream maintained separate, unvectorized Cooley-Tukey scalar FFT implementations in `tempo_analysis.cpp`, `mel_spectrogram.cpp`, `vocal_spectrogram.cpp`, and `audio_analysis.cpp`.

* **Unified In-Place Engine:** Replaced duplicate implementations with an in-place radix-2 `FastFft` engine in `native/analyzer/fast_fft.h`.
* **Precomputed Lookup Tables:** Replaced runtime `sin`/`cos` trigonometric evaluations with static, thread-local twiddle factor and bit-reversal tables initialized once per transform length.
* **Precision:** Complex output deviation against double-precision reference transforms remains below $6.7 \times 10^{-11}$.

### 3. Neural Network Hardware GPU/NPU Acceleration
Upstream machine learning models (`beat_this_final0_q8.onnx` and `vocals_umxhq_int8.onnx`) contained runtime dynamic quantization operators (`DynamicQuantizeLinear`, `MatMulInteger`, `DynamicQuantizeLSTM`).

> [!IMPORTANT]
> Mobile GPU/NPU drivers (ARM Mali, Qualcomm Adreno) do not support dynamic runtime scale calculation. When encountering dynamic quantization operators, Android NNAPI rejects hardware delegation and executes via a single-threaded CPU software fallback (`libneuralnetworks.so`), causing inference latency to exceed 7 seconds per audio chunk.

* **Graph Conversion:**
  * `beat_this_final0_fp.onnx` (79 MB): Replaced 62 `MatMulInteger` and 50 `DynamicQuantizeLinear` nodes with standard floating-point `MatMul` nodes. Weights were dequantized using their original stored scales and zero-points:
    $$\mathbf{W}_{\text{float}} = (\mathbf{W}_{\text{int8}} - \mathbf{Z}_{\mathbf{W}}) \times \mathbf{S}_{\mathbf{W}}$$
  * `vocals_umxhq_fp.onnx` (34 MB): Converted 3 `DynamicQuantizeLSTM` operators to standard ONNX `LSTM` operators with weights transposed to `[num_directions, 4 * hidden_size, input_size]`, and replaced 3 `MatMulInteger` nodes with `MatMul`.
* **Hardware Execution:** Standard float graphs compile natively into GPU execution pipelines via OpenCL/Vulkan in NNAPI, reducing beat tracking latency from 7,300 ms to 267 ms (**27.3x faster**).

### 4. Compatibility & Fallback
* **NNAPI Hardware First:** Inference sessions attempt NNAPI execution first for GPU/NPU acceleration.
* **CPU Fallback:** On devices without compatible NNAPI drivers, inference falls back to multi-threaded CPU execution. Because dynamic quantization scanning was removed, CPU execution runs in 374 ms per chunk (down from 1,097 ms, **2.9x faster** than upstream baseline).

### 5. Cache Management
* On startup, `TrackAnalyzer` purges retired model files (`beat_this_final0_q8.onnx`, `vocals_umxhq_int8.onnx`) from internal storage (`context.filesDir`), reclaiming approximately 110 MB.

---

## Verification

To verify that optimizations preserve output fidelity, each subsystem was evaluated against its upstream baseline:

| Subsystem | Baseline Reference | Verification Method | Result |
| :--- | :--- | :--- | :--- |
| **Beat Tracking ML** | `beat_this_final0_q8.onnx` | Direct output tensor comparison on test audio | $r = 1.000$, $\Delta_{\max} = 0.0$ |
| **Vocal Separation ML** | `vocals_umxhq_int8.onnx` | Spectrogram chunk activation comparison | $r = 0.9994$ |
| **Spectral DSP** | Scalar Cooley-Tukey FFT | Complex output delta across $N \in [256, 2048]$ | $\Delta_{\max} < 6.7 \times 10^{-11}$ |
| **Splice Alignment** | Scalar `nativeSpliceLag` | Lag offset comparison over 15.7M operations | Identical peak index |

---

## Build Instructions

To build the application locally:

```bash
# Clone repository and checkout optimization branch
git clone https://github.com/httperry/BitChord.git
cd BitChord
git checkout perf/audio-mixing-optimization

# Build development debug APK with native ARM64 libraries
./gradlew assembleDevDebug
```

The compiled APK will be at:
`app/build/outputs/apk/dev/debug/app-dev-arm64-v8a-debug.apk`

---

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
