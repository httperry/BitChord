# BitChord Liquid Glass Performance, Thermal & Architectural Audit (v2)

> **Document Status:** Technical Architecture Diagnosis & Peer Review Revision  
> **Target Module:** `app/src/main/java/com/music/bitchord/ui/components/` & vendored `backdrop`  
> **Target Hardware Baseline:** OnePlus Nord CE5 / MediaTek Dimensity 8350 (ARM Mali-G615 MC6, 120 Hz AMOLED display, Android 16)  
> **Core Visual Constraint:** **100% preservation of the 7-color dispersion spectrum.** No color bands (orange, yellow, cyan, purple) may be dropped or approximated.

---

## 1. Critique Reconciliation & Corrections

Following peer review of the preliminary diagnosis, several technical claims and attributions have been formally revised:

| Preliminary Claim | Peer Critique Finding | Revised Reality & Correction |
|---|---|---|
| **"11× gap driven by glass"** | Screen-off vs screen-on conflates display panel, touch polling, CPU boosting, and general Compose rendering with glass. | **Correct.** The 555 mA reading is total device foreground drain. Glass-on vs Glass-off in the exact same foreground state must be measured via a controlled A/B test. |
| **"$\Delta T = +2.8^\circ\text{C}$ against ambient"** | 33.4°C is an internal battery thermistor, not ambient room temperature. The foreground rise was $+1.5^\circ\text{C}$ (33.4°C $\to$ 34.9°C). | **Correct.** Thermal telemetry reflects device chassis/battery heat accumulation over 3 songs ($+1.5^\circ\text{C}$), not ambient temperature. |
| **"3-Tap RGB Dispersion"** | Dropping to 3 taps removes orange, yellow, cyan, and purple bands, creating hard red/blue chromatic splitting and ghosting. | **Rejected.** Per user requirement and optical fidelity, **the 7-tap dispersion spectrum is retained 100% intact**. |
| **"Texture cache thrashing & dependent reads"** | UV offsets vary smoothly across adjacent fragments; spatial coherency is maintained. Warps only diverge at the boundary margin. | **Corrected.** Texture reads are spatially coherent across adjacent fragments. Far-interior pixels take the fast branch together. The ALU/TEX overhead is concentrated in the refracted margin, not the flat interior. |
| **"Pill shader is the main cost"** | Glass pills are small screen sub-regions. The primary overhead is re-recording the entire 1.5K page into `backdrop.graphicsLayer` 40×/s. | **Affirmed & Refocused.** The dominant system-level bottleneck is continuous full-page layer re-recording driven by ambient animation loops. |

---

## 2. Controlled Isolation Protocol: Glass-ON vs Glass-OFF

To definitively isolate the energy and GPU cost of Liquid Glass from the AMOLED display and base UI rendering, the following A/B control test must be executed under identical conditions:

### Test Parameters:
- **Display Brightness:** Fixed at 50% manual brightness (disable auto-brightness).
- **Refresh Rate:** 120 Hz locked.
- **Audio State:** Playing the same 3-track album in loop, volume at 0 (or constant level).
- **Foreground Screen:** Sitting on the Home tab (displaying `TopPicksShelf` and the bottom navigation bar).
- **Duration:** 15 minutes per test run.

### Measurement Procedure:
```bash
# 1. Reset battery and gfxinfo stats
rish -c "dumpsys batterystats --reset"
rish -c "dumpsys gfxinfo com.music.bitchord reset"

# 2. Run Test A: Liquid Glass ENABLED (Settings -> Liquid Glass = ON)
# Let run for 15 minutes on Home tab with screen on.
rish -c "dumpsys gfxinfo com.music.bitchord framestats" > /storage/emulated/0/Download/gfx_glass_on.txt
rish -c "dumpsys batterystats" > /storage/emulated/0/Download/battery_glass_on.txt

# 3. Reset stats again
rish -c "dumpsys batterystats --reset"
rish -c "dumpsys gfxinfo com.music.bitchord reset"

# 4. Run Test B: Liquid Glass DISABLED (Settings -> Liquid Glass = OFF)
# Exactly the same 15 minutes on Home tab with screen on.
rish -c "dumpsys gfxinfo com.music.bitchord framestats" > /storage/emulated/0/Download/gfx_glass_off.txt
rish -c "dumpsys batterystats" > /storage/emulated/0/Download/battery_glass_off.txt
```

This isolates:
$$\text{Glass Power Drain} = I_{\text{Glass ON}} - I_{\text{Glass OFF}}$$
$$\text{GPU Jank / Frame Time Impact} = t_{\text{frame, Glass ON}} - t_{\text{frame, Glass OFF}}$$

---

## 3. Structural Root Cause: Full-Page Backdrop Churn

While the glass surfaces cover small screen areas, their presence fundamentally alters how the **entire application** renders:

```mermaid
flowchart TD
    subgraph IdleLoop["1. The Ambient Loop (TopPicksShelf.kt)"]
        StationArt["PersonalStationArt / DiscoveryStationArt"] -->|withFrameNanos + delay(25ms)| Phase["phase.floatValue updated 40x/sec"]
    end

    subgraph EntireApp["2. Full-Page Re-Recording (MainActivity.kt)"]
        Phase -->|Invalidates Canvas| RootContent["AnimatedContent (Main Page Container)"]
        RootContent -->|Wrapped in Modifier.layerBackdrop| FullLayer["appBackdrop.graphicsLayer (1220x2712 px)"]
        FullLayer -->|Unconditional full redraw| Blit["drawLayer(backdrop.graphicsLayer)"]
    end

    subgraph GlassSurfaces["3. Bottom Bar Re-Evaluation (GlassNavBar.kt)"]
        Blit -->|appBackdrop dirty signal| S1["Tab Bar Pill"]
        Blit -->|appBackdrop dirty signal| S2["Search Circle"]
        Blit -->|appBackdrop dirty signal| S3["NowPlaying Accessory"]
        S1 & S2 & S3 --> Shaders["2-Pass Gaussian Blur + 7-Color AGSL Dispersion + Specular Highlight"]
    end
```

### The Mechanism of Continuous Invalidation:
1. In [`MainActivity.kt`](file:///data/user/0/com.agy/files/antigravity/beautiful-planck/app/src/main/java/com/music/bitchord/MainActivity.kt#L2858-L2864), the entire screen hierarchy (`AnimatedContent`) is wrapped with `Modifier.layerBackdrop(appBackdrop)` whenever `liquidGlass` is on.
2. In [`TopPicksShelf.kt`](file:///data/user/0/com.agy/files/antigravity/beautiful-planck/sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/TopPicksShelf.kt#L663-L683), decorative covers run `loopPhase` on a 25 ms tick (`MOTION_FRAME_GAP_MS = 25L` in [`Common.kt`](file:///data/user/0/com.agy/files/antigravity/beautiful-planck/sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt#L730)).
3. Every 25 ms, `phase.floatValue` updates, invalidating a decorative canvas at the top of the feed.
4. Because the canvas is inside `layerBackdrop(appBackdrop)`, **the entire 1.5K page ($1220 \times 2712\text{ px}$) re-records into `backdrop.graphicsLayer`**.
5. In [`GlassNavBar.kt`](file:///data/user/0/com.agy/files/antigravity/beautiful-planck/app/src/main/java/com/music/bitchord/ui/components/GlassNavBar.kt#L136), there are **three separate glass surfaces** (the tab pill, the circular search tab, and the mini-player accessory capsule).
6. Because `appBackdrop` is marked dirty, all three glass surfaces at the bottom of the screen re-run their backdrop capture, separable Gaussian blur, and 7-color dispersion AGSL shader **40 times every second while the user is not touching the phone**.

---

## 4. Zero-Visual-Loss Optimization Architecture

To maintain **100% of the visual fidelity** while eliminating unnecessary GPU workload, optimizations must focus on **architectural invalidation isolation, filtered downscaling, and layer pass pruning**:

```
+---------------------------------------------------------------------------------------------------------+
|                                    TARGETED ARCHITECTURAL SOLUTIONS                                     |
+---------------------------------------------------+-----------------------------------------------------+
| Optimization                                      | Visual Fidelity Guarantee                           |
+---------------------------------------------------+-----------------------------------------------------+
| 1. Retain Full 7-Color Dispersion Spectrum        | 100% Identical optical spectrum (all 7 bands kept)  |
| 2. Break Full-Page Backdrop Churn (Invalidation)  | 100% Bit-identical on static frames (zero redraws)  |
| 3. Filtered 0.25x Backdrop Scaling                | Identical blur frequency content; filtered sampling |
| 4. Direct Highlight Draw (Drop Offscreen Layer)   | Identical specular rim without extra FBO pass       |
| 5. Ambient Loop Inactivity Throttling             | 100% Bit-identical when idle                        |
+---------------------------------------------------+-----------------------------------------------------+
```

### Strategy 1: Retain 100% of the 7-Color Dispersion Spectrum
The full 7-tap AGSL shader in [`Shaders.kt`](file:///data/user/0/com.agy/files/antigravity/beautiful-planck/app/src/main/java/com/music/bitchord/ui/components/backdrop/internal/Shaders.kt#L134-L165) is preserved without alteration:
- Red (`refractedCoord + dispersedCoord`)
- Orange (`refractedCoord + dispersedCoord * 2/3`)
- Yellow (`refractedCoord + dispersedCoord * 1/3`)
- Green (`refractedCoord`)
- Cyan (`refractedCoord - dispersedCoord * 1/3`)
- Blue (`refractedCoord - dispersedCoord * 2/3`)
- Purple (`refractedCoord - dispersedCoord`)

No color bands are dropped. The physical rainbow aberration remains identical to the current design.

### Strategy 2: Eliminate Idle Screen Churn (Backdrop Isolation)
Rather than executing 40 full-page redraws per second when sitting on the Home screen:
1. **Viewport Invalidation Isolation:** When the page content is static (not scrolling), ambient decorative loops in `TopPicksShelf` (which are physically located at the top of the feed) should not dirty the bottom navigation bar's backdrop slice.
2. **Backdrop State Memoization:** In `DrawBackdropModifier.kt`, cache the recorded backdrop layer slice. If the bottom viewport coordinates and the list scroll state are stationary, skip the re-recording pass and blit the existing cached layer.
- **Visual Impact:** **$0.00\%$ visual change (100% bit-identical)**.
- **GPU Impact:** Eliminates up to 160 heavy shader executions per second during idle screen states.

### Strategy 3: Filtered $0.25\times$ Input Scaling
- In [`LiquidGlass.kt`](file:///data/user/0/com.agy/files/antigravity/beautiful-planck/app/src/main/java/com/music/bitchord/ui/components/LiquidGlass.kt#L81), `GLASS_RESOLUTION_SCALE = 0.33f`.
- Lowering this to `0.25f` reduces processed fragments by **$42.6\%$**:
  $$\text{Pixel Ratio} = \left(\frac{0.25}{0.33}\right)^2 \approx 0.574 \implies \mathbf{42.6\%\text{ fewer pixels}}.$$
- **Critical Implementation Requirement:**
  - The blur standard deviation $\sigma$ must be scaled proportionally in real pixels.
  - The downscale texture sampling must use bilinear filtering to prevent shimmering/aliasing artifacts during scroll.
  - Because Gaussian blur ($\sigma \approx 24\text{ px}$) attenuates frequencies above $0.03\text{ cycles/px}$, frequency content above this threshold is physically negligible under the blur.

### Strategy 4: Direct Highlight Draw (Eliminating Offscreen Layer Allocation)
- In [`HighlightModifier.kt`](file:///data/user/0/com.agy/files/antigravity/beautiful-planck/app/src/main/java/com/music/bitchord/ui/components/backdrop/highlight/HighlightModifier.kt#L128-L136), `HighlightNode` creates an offscreen `highlightLayer` (`GraphicsLayer`), clips it, runs the specular AGSL shader, and blits it back.
- **Optimization:** Draw the specular highlight stroke directly on the canvas during the surface draw pass using the existing shader and blend mode, eliminating the separate offscreen FBO allocation per surface.
- **Visual Impact:** Identical specular highlight; eliminates 1 offscreen buffer allocation per surface per frame.

---

## 5. Next Steps for Implementation on `perf/liquid-glass-optimization`

1. **Step 1: Baseline Control Test**
   - Measure 15-minute Glass-ON vs Glass-OFF using `dumpsys batterystats` and `dumpsys gfxinfo` to determine the exact delta attributable to Liquid Glass.
2. **Step 2: Implement Filtered $0.25\times$ Scaling & Direct Highlight**
   - Retain all 7 color taps in `Shaders.kt`.
   - Update `GLASS_RESOLUTION_SCALE = 0.25f` with bilinear filtering.
   - Refactor `HighlightNode` to remove the offscreen layer allocation.
3. **Step 3: Implement Viewport Backdrop Caching**
   - Prevent top-of-page canvas animations from triggering full-screen re-records when the bottom navigation bar is static.
4. **Step 4: Re-Benchmark & Verify**
   - Confirm identical visual output via frame captures and confirm temperature stabilization.
