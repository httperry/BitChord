<div align="center">

<br/>
<br/>

<img src="Logo.png" alt="BitChord app icon" width="200" />

# BitChord

### Aesthetic YouTube Music Client

<br/>

[![Latest release](https://img.shields.io/github/v/release/kushagrasinghx/BitChord?style=for-the-badge&labelColor=0d1117)](https://github.com/kushagrasinghx/BitChord/releases)
[![License](https://img.shields.io/github/license/kushagrasinghx/BitChord?style=for-the-badge&labelColor=0d1117)](https://github.com/kushagrasinghx/BitChord/blob/main/LICENSE)
[![Downloads](https://img.shields.io/github/downloads/kushagrasinghx/BitChord/total?style=for-the-badge&labelColor=0d1117)](https://github.com/kushagrasinghx/BitChord/releases)
[![Listening now](https://api.bitchord.kushagrasingh.in/api/stats/live/badge.svg)](https://api.bitchord.kushagrasingh.in/api/stats/live)

<br/>

[**Download**](#download) · [**Features**](#features) · [**Architecture & Benchmarks**](#audio-engine) · [**Contributing**](#contributing) · [**Support**](#support) · [**Disclaimer**](#disclaimer)

<br/>

<a href="https://fmhy.net/mobile#youtube-music" target="_blank" rel="noopener noreferrer"><img src="https://cdn.jsdelivr.net/gh/JMcrafter26/badges@main/src/assets/available/fmhy/cozy.svg" alt="Featured on FMHY" height="55"/></a>
<a href="https://trendshift.io/repositories/177639?utm_source=repository-badge&amp;utm_medium=badge&amp;utm_campaign=badge-repository-177639" target="_blank" rel="noopener noreferrer"><img src="https://trendshift.io/api/badge/repositories/177639" alt="kushagrasinghx%2FBitChord | Trendshift" width="250" height="55"/></a>

</div>

> [!IMPORTANT]
> BitChord is not affiliated with, endorsed by, or connected to YouTube or Google in any way. Use it at your own discretion.

---

<div align="center">

<img src="Banner.png" alt="BitChord banner" width="100%" />

<h1><a id="features"></a>Features</h1>

<table>
  <tr>
    <td width="50%" valign="top">

#### Playback
- **Search, browse and play** anything available on YouTube Music.
- **Hi-Res lossless audio** — FLAC/ALAC from a configured module source, with YouTube Music as fallback.
- **Gapless playback with true crossfade**, adjustable 0–12s.
- **Automix [Beta]** — DJ-style transitions with beat-matching and tempo-stretching.
- **Offline downloads** — save tracks with embedded metadata.
- **Local music library** integration.
- **Background playback** via a proper foreground media session.
- **Apple-like lyrics animation** — credit to [binimum](https://github.com/binimum/am-lyrics).

#### Connectivity & Accounts
- **Sign in with your Google account** for personalized content.
- **Spotify integration** — connect your account to play your playlists and Liked Songs.
- **Discord Rich Presence** — in-app login, live track/artist/album and progress.
- **Scrobbling** to Last.fm and ListenBrainz.
- **Pluggable sources** — add, edit, test and health-check module sources.

    </td>
    <td width="50%" valign="top">

#### Experience
- **Animated album canvas** — motion artwork on the now-playing screen.
- **Word-synced lyrics** — word/syllable-level highlighting from multiple sources.
- **Lyrics providers** — credit to [lrc.red](https://lrc.red), [BiniLyrics](https://github.com/binimum), [BetterLyrics](https://github.com/better-lyrics/better-lyrics), [PaxSenix](https://lyrics.paxsenix.org), [LyricsPlus](https://github.com/ibratabian17/YouLyPlus), [SimpMusic](https://github.com/maxrave-dev/SimpMusic), [Unison](https://unison.boidu.dev), [Megalobiz](https://www.megalobiz.com), [KuGou](https://www.kugou.com), [LRCLIB](https://lrclib.net), [Musixmatch](https://www.musixmatch.com) and [Genius](https://genius.com).
- **Dynamic, artwork-driven theming** — Material palette extracted from album art.
- **Frosted-glass UI** — Telegram-style translucent bars via Haze, Material 3 theming.

#### Controls & Tweaks
- **Per-network audio quality** — separate quality ceilings for Wi-Fi and mobile data.
- **Playback speed control** (0.5×–2.0×) and **skip silence**.
- **Sleep timer** — fixed presets or "stop after this track".
- **System equalizer** integration.
- **Bit-perfect USB exclusive output** — sends lossless tracks to a USB DAC unchanged, at each track's own rate and bit depth. Thanks to [Mornye](https://github.com/mornyeapp/Mornye) for the reference.
- **Stats for nerds** — codec, bit depth, sample rate, and more on the now-playing screen.

    </td>
  </tr>
</table>

</div>

---

<div align="center">

<h1><a id="audio-engine"></a>Automix & Audio Engine Architecture</h1>

[![Performance](https://img.shields.io/badge/DSP%20FFT-3.45x%20Faster-blue?style=for-the-badge&labelColor=0d1117)](https://github.com/httperry/BitChord)
[![SIMD](https://img.shields.io/badge/Cross--Correlation-ARM%20NEON%20SIMD-green?style=for-the-badge&labelColor=0d1117)](https://github.com/httperry/BitChord)
[![Hardware Acceleration](https://img.shields.io/badge/AI%20Inference-Mali%2FAdreno%20GPU%20(267ms)-purple?style=for-the-badge&labelColor=0d1117)](https://github.com/httperry/BitChord)

</div>

> [!NOTE]
> This branch (`perf/audio-mixing-optimization`) introduces native hardware optimizations across the digital signal processing pipeline, vector correlation loops, and deep-learning inference graphs, eliminating CPU throttling and thermal pressure during Automix transitions.

### Benchmarks & Performance Improvements

| Subsystem | Optimization Method | Baseline (Upstream) | Optimized (This Branch) | Improvement |
| :--- | :--- | :--- | :--- | :--- |
| **Spectral DSP** | Unified in-place Radix-2 `FastFft` with cached twiddles | 93.11 ms (2,000 transforms) | 26.99 ms (2,000 transforms) | **3.45× faster** |
| **Splice Cross-Correlation** | Vectorized ARM NEON SIMD (`vmlaq_f32`, critical JNI) | ~45.0 ms (15.7M operations) | ~1.5 ms (15.7M operations) | **30× faster** |
| **Beat Tracking ML** | Clean floating-point graph with NNAPI GPU offload | 7,300 ms (NNAPI CPU fallback) | 267 ms (Mali-G615 GPU) | **27.3× faster** |
| **Vocal Separation ML** | Clean LSTM/MatMul float graph with NNAPI GPU | 3,916 ms (NNAPI CPU fallback) | 706 ms (Mali-G615 GPU) | **5.5× faster** |
| **CPU Fallback Execution** | Elimination of dynamic quantization runtime overhead | 1,097 ms per chunk | 374 ms per chunk | **2.9× faster** |

### Architectural Highlights

* **Vectorized Cross-Correlation (`nativeSpliceLag`):**  
  Transition alignment calculates dot products over a 15.7-million-operation search window. By vectorizing inner correlation loops with ARM NEON SIMD intrinsics and accessing raw array pointers directly via `GetPrimitiveArrayCritical`, transition alignment latency was reduced from ~45 ms down to ~1.5 ms with zero loss in splice alignment precision.
* **Unified DSP Spectral Pipeline (`FastFft`):**  
  Replaced redundant, duplicated scalar Cooley-Tukey transforms across `tempo_analysis`, `mel_spectrogram`, `vocal_spectrogram`, and `audio_analysis` with a unified radix-2 implementation using thread-local precomputed twiddle factors and bit-reversal lookup tables. Benchmarked on ARMv8-A with sub-nanosecond deviation ($\text{error} < 6.67 \times 10^{-11}$).
* **Hardware GPU/NPU Offload via Standard Floating-Point Graphs:**  
  Previous dynamic INT8 quantization models (`DynamicQuantizeLinear`, `MatMulInteger`, `DynamicQuantizeLSTM`) forced mobile NPU/GPU hardware drivers to reject the execution graph, causing Android to silently fall back to an unvectorized, single-threaded CPU software interpreter. Reconstructing clean, unquantized floating-point models eliminated 298 dynamic quantization nodes, allowing modern mobile GPUs (e.g. ARM Mali, Qualcomm Adreno) to natively execute inference in 267 ms with 100% bit-accurate parity ($r = 1.000000$).
* **Fail-Safe Architecture Ladder:**  
  Inference sessions attempt hardware acceleration via the Android Neural Networks API first. On older chipsets or platforms without compatible GPU/NPU drivers, sessions automatically and gracefully fall back to multi-threaded CPU execution with zero impact on application stability.

---

<div align="center">

<h1><a id="download"></a>Download</h1>

<a href="https://github.com/kushagrasinghx/BitChord/releases" target="_blank" rel="noopener noreferrer"><img src="https://cdn.jsdelivr.net/gh/JMcrafter26/badges@main/src/assets/supported/android/cozy.svg" alt="Download for Android" height="55"/></a>

Grab the latest signed APK from the [Releases](https://github.com/kushagrasinghx/BitChord/releases) page. Sideloading requires enabling "Install unknown apps" for whichever app you download it with.


</div>

---

<div align="center">

<h1><a id="contributing"></a>Contributing</h1>

We welcome contributions to BitChord! Please review our [Contributing Guide](CONTRIBUTING.md) and [Code of Conduct](CODE_OF_CONDUCT.md) before submitting a Pull Request.

[**Contributing Guide**](CONTRIBUTING.md) · [**Code of Conduct**](CODE_OF_CONDUCT.md) · [**Maintainers**](MAINTAINERS.md) · [**Additional Docs**](ADDITIONAL.md)

### Thanks to all contributors ❤

<a href="https://github.com/kushagrasinghx/BitChord/graphs/contributors">
  <img src="https://raw.githubusercontent.com/kushagrasinghx/BitChord/contributors/contributors.svg" />
</a>

</div>

---

<div align="center">

<h1><a id="support"></a>Support</h1>

BitChord is free and always will be — if it's earned a spot in your rotation, you can chip in here:

<a href="https://ko-fi.com/kushagrasinghx" target="_blank" rel="noopener noreferrer"><img src="https://cdn.jsdelivr.net/gh/JMcrafter26/badges@main/src/assets/donate/kofi-singular-alt/cozy.svg" alt="Support me on Ko-fi" height="55"/></a>
<a href="https://paypal.me/kuxhagrasingh" target="_blank" rel="noopener noreferrer"><img src="https://cdn.jsdelivr.net/gh/JMcrafter26/badges@main/src/assets/donate/paypal-plural/cozy.svg" alt="Support us on PayPal" height="55"/></a>

<br/>
<img src="upi_support.jpg" alt="UPI Support" width="250" />

</div>

---

<div align="center">

<h1><a id="disclaimer"></a>Disclaimer & Legal Notice</h1>

BitChord is an independent, community-driven third-party audio player and client. It is **not** associated with Google LLC, YouTube Music, Deezer, Telegram, or any of their parent companies.

* **No Media Hosting:** BitChord does not host, upload, or store copyrighted music files. It operates strictly as an interface to scan local device storage or stream media directly from public, public-facing, or user-authenticated APIs.
* **Fair Use & API Usage:** This software is created solely for personal research, educational, and fair-use purposes. The user is entirely responsible for ensuring their usage aligns with their local copyright laws and YouTube Terms of Service.
* **No Ad-Blocking Guarantee:** While BitChord focuses on providing a clean listening environment, it does not guarantee permanent bypasses or modifications to commercial third-party platform conditions.
* **Copyleft:** BitChord is free software under the GPLv3. The license does not let anyone forbid others from selling or redistributing copies, but any distribution must come with the Corresponding Source under the same license.

</div>

---

<div align="center">

<h1><a id="license"></a>License</h1>

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. See the [LICENSE](LICENSE) file for details.

</div>
