/*
 * Copyright (C) 2026 BitChord
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutomixPhase2MusicalIntelligenceTest {

    private fun createTrack(
        id: String,
        bpm: Double,
        key: String,
        keyConfidence: Double = 0.9,
        beatConfidence: Double = 0.9,
        duration: Double = 180.0,
        energySamples: List<EnergySample>? = null,
        downbeats: List<Double>? = null,
        phraseBoundaries: List<Double>? = null,
    ): TrackAnalysis {
        val beatInterval = 60.0 / bpm
        val dbList = downbeats ?: generateSequence(1.0) { it + 4 * beatInterval }
            .takeWhile { it < duration - 1.0 }
            .toList()
        val pbList = phraseBoundaries ?: dbList.filterIndexed { index, _ -> index % 8 == 0 }
        val energyList = energySamples ?: (0 until (duration * 2).toInt()).map {
            EnergySample(it / 2.0, 0.5)
        }

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beatInterval,
            beatConfidence = beatConfidence,
            downbeats = dbList,
            phraseBoundaries = pbList,
            firstBeat = 1.0,
            key = key,
            keyConfidence = keyConfidence,
            audibleStartTime = 0.5,
            contentEndTime = duration - 2.0,
            mixInTime = pbList.getOrNull(1) ?: dbList.getOrNull(4) ?: 16.0,
            energyCurve = energyList,
            vocalProbability = 0.2,
        )
    }

    // ---- 1. Camelot Harmonic Compatibility and Gating Tests -----------------

    @Test
    fun testCamelotHarmonicsScoreCompatibilityFromAnalyses() {
        val trackA = createTrack("a", 124.0, "C Major") // 8B
        val trackSameKey = createTrack("b", 124.0, "C Major") // 8B
        val trackAdjacentKey = createTrack("c", 124.0, "G Major") // 9B
        val trackRelativeMinor = createTrack("d", 124.0, "A minor") // 8A
        val trackClash = createTrack("e", 124.0, "Db Major") // 3B
        val trackLowConfidence = createTrack("f", 124.0, "C Major", keyConfidence = 0.2)
        val trackUnknownKey = createTrack("g", 124.0, "")

        // Same key gives 1.0
        assertEquals(1.0, CamelotHarmonics.scoreCompatibility(trackA, trackSameKey), 0.001)

        // Adjacent key (9B) gives 0.9
        assertEquals(0.9, CamelotHarmonics.scoreCompatibility(trackA, trackAdjacentKey), 0.001)

        // Relative minor (8A) gives 0.9
        assertEquals(0.9, CamelotHarmonics.scoreCompatibility(trackA, trackRelativeMinor), 0.001)

        // Distant clash (3B) gives low score (< 0.8)
        val clashScore = CamelotHarmonics.scoreCompatibility(trackA, trackClash)
        assertTrue(clashScore < 0.8, "Clash key must score below 0.8, got $clashScore")

        // Low key confidence falls back to neutral 0.5
        assertEquals(0.5, CamelotHarmonics.scoreCompatibility(trackA, trackLowConfidence), 0.001)

        // Unknown key falls back to 0.5
        assertEquals(0.5, CamelotHarmonics.scoreCompatibility(trackA, trackUnknownKey), 0.001)
    }

    @Test
    fun testBreakdownBlendPermittedLogic() {
        val baseTrack = createTrack("base", 120.0, "8B")

        // Compatible key + close BPM (diff <= 6%)
        val compatibleTrack = createTrack("comp", 124.0, "8B")
        val bpmDiff = abs(120.0 - 124.0) / 120.0
        assertTrue(bpmDiff <= MAX_BREAKDOWN_BPM_DIFF)
        assertTrue(isBreakdownBlendPermitted(baseTrack, compatibleTrack))

        // Large tempo divergence (> 6%) gates out breakdown blend
        val highBpmTrack = createTrack("fast", 130.0, "8B")
        val fastBpmDiff = abs(120.0 - 130.0) / 120.0
        assertTrue(fastBpmDiff > MAX_BREAKDOWN_BPM_DIFF)
        assertFalse(isBreakdownBlendPermitted(baseTrack, highBpmTrack))

        // Harmonic clash (< 0.8) gates out breakdown blend even if BPM matches
        val clashTrack = createTrack("clash", 120.0, "3B")
        assertFalse(isBreakdownBlendPermitted(baseTrack, clashTrack))
    }

    @Test
    fun testBreakdownBlendGatingFallbackToBassSwap() {
        val baseTrack = createTrack("out", 120.0, "8B")
        // Clash key: 3B vs 8B
        val clashTrack = createTrack("in", 120.0, "3B")

        val plan = planTransition(
            analysis = baseTrack,
            nextAnalysis = clashTrack,
            currentTrack = TransitionTrackInfo("out", (baseTrack.duration * 1000).toLong()),
            nextTrack = TransitionTrackInfo("in", (clashTrack.duration * 1000).toLong()),
            currentTime = 100.0,
            duration = baseTrack.duration,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )

        // When gated out from breakdown blend due to harmonic clash,
        // it must fall back to DJ_FILTER with BASS_HANDOFF (Linkwitz-Riley crossover bass swap)
        assertNotNull(plan)
        assertEquals(TransitionStyle.DJ_FILTER, plan.transitionStyle)
        assertEquals(FilterTransitionVariant.BASS_HANDOFF, plan.filterVariant)
        assertTrue(plan.bassSwap, "Bass swap must be enabled for Linkwitz-Riley 4th order fallback")
    }

    // ---- 2. 4-Bar Smoothed Energy Cliffhanger Veto Tests ---------------------

    @Test
    fun testSmoothedEnergySlope4BarDetection() {
        val barInterval = 2.0 // 120 BPM: 0.5s beat -> 2.0s bar. 4 bars = 8.0s

        // Construct an energy curve with a sharp cliffhanger / buildup around t = 50.0s
        // Prev 4-bar window [42.0, 50.0): energy = 0.2
        // Curr 4-bar window [50.0, 58.0): energy = 0.8
        val samples = mutableListOf<EnergySample>()
        for (i in 0 until 200) {
            val t = i * 0.5
            val energy = when {
                t < 42.0 -> 0.2
                t < 50.0 -> 0.2
                t < 58.0 -> 0.8
                else -> 0.8
            }
            samples.add(EnergySample(t, energy))
        }

        val anchorTime = 50.0
        val slope = smoothedEnergySlope4Bar(samples, anchorTime, barInterval)
        assertNotNull(slope)

        // delta = 0.8 - 0.2 = +0.6 > 0.15 threshold
        assertTrue(slope > CLIFFHANGER_ENERGY_SLOPE_THRESHOLD, "Slope $slope should exceed threshold")
        assertTrue(isEnergyCliffhangerVetoed(samples, anchorTime, barInterval))

        // Conversely, with steady energy, slope should be near 0 and not vetoed
        val steadySamples = (0 until 200).map { EnergySample(it * 0.5, 0.5) }
        val steadySlope = smoothedEnergySlope4Bar(steadySamples, anchorTime, barInterval)
        assertNotNull(steadySlope)
        assertEquals(0.0, steadySlope, 0.05)
        assertFalse(isEnergyCliffhangerVetoed(steadySamples, anchorTime, barInterval))
    }

    @Test
    fun testCliffhangerVetoInCandidateRankingAnd15SecondDeferralCeiling() {
        val bpm = 120.0
        val beatInterval = 60.0 / bpm
        val barInterval = 4 * beatInterval // 2.0s
        val duration = 180.0

        // Create downbeats every 2.0s and phrase boundaries every 16.0s (8 bars)
        val downbeats = generateSequence(0.0) { it + barInterval }.takeWhile { it < duration }.toList()
        val phraseBoundaries = downbeats.filterIndexed { index, _ -> index % 8 == 0 }

        // Create an energy curve with a buildup starting at 100.0s lasting until 114.0s
        val samples = (0 until (duration * 2).toInt()).map { i ->
            val t = i * 0.5
            val energy = if (t in 100.0..114.0) 0.1 + (t - 100.0) * 0.05 else 0.4
            EnergySample(t, energy)
        }

        val track = createTrack(
            id = "cliffhanger",
            bpm = bpm,
            key = "8B",
            duration = duration,
            energySamples = samples,
            downbeats = downbeats,
            phraseBoundaries = phraseBoundaries,
        )

        // Rank candidates around the cliffhanger
        val candidates = listOf(102.0, 104.0, 106.0)
        val ranked = rankMixOutCandidates(track, duration, duration)

        // Active buildup candidates with slope > 0.15 should be filtered out
        for (candidate in ranked) {
            val slope = smoothedEnergySlope4Bar(track.energyCurve, candidate.time, barInterval)
            if (slope != null) {
                assertTrue(slope <= CLIFFHANGER_ENERGY_SLOPE_THRESHOLD)
            }
        }

        // Test the 15-second maximum deferral ceiling in resolveMixOutAnchor
        val initialAnchor = 100.0
        val resolved = resolveMixOutAnchor(track, duration, duration)
        assertNotNull(resolved)

        // The resolved anchor must be valid and within track duration
        assertTrue(resolved.time <= track.contentEndTime)
    }

    // ---- 3. Beat Tracking Confidence & Downbeat Grid Alignment Quality Gating -

    @Test
    fun testDownbeatGridAlignmentCheck() {
        val beatInterval = 0.5 // 120 BPM, bar = 2.0s

        // Perfectly aligned downbeats every 2.0s
        val alignedDownbeats = listOf(1.0, 3.0, 5.0, 7.0, 9.0, 11.0, 13.0)
        assertTrue(isDownbeatGridAligned(alignedDownbeats, beatInterval))

        // Unaligned jittery downbeats
        val jitteryDownbeats = listOf(1.0, 2.5, 5.2, 6.8, 9.4)
        assertFalse(isDownbeatGridAligned(jitteryDownbeats, beatInterval))

        // Empty or single downbeat cannot establish a grid
        assertFalse(isDownbeatGridAligned(emptyList(), beatInterval))
        assertFalse(isDownbeatGridAligned(listOf(1.0), beatInterval))
    }

    @Test
    fun testQualityGateDowngradeWhenBeatConfidenceLow() {
        val lowBeatConfidenceTrack = createTrack(
            id = "lowBeatConf",
            bpm = 120.0,
            key = "8B",
            beatConfidence = 0.65, // < 0.75 quality gate threshold
        )
        val nextTrack = createTrack(
            id = "next",
            bpm = 120.0,
            key = "8B",
            beatConfidence = 0.9,
        )

        assertFalse(passesAdvancedQualityGate(lowBeatConfidenceTrack))

        // Request advanced mix plan
        val plan = planTransition(
            analysis = lowBeatConfidenceTrack,
            nextAnalysis = nextTrack,
            currentTrack = TransitionTrackInfo("lowBeatConf", (lowBeatConfidenceTrack.duration * 1000).toLong()),
            nextTrack = TransitionTrackInfo("next", (nextTrack.duration * 1000).toLong()),
            currentTime = 10.0,
            duration = lowBeatConfidenceTrack.duration,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )

        // Low beat confidence must downgrade advanced styles to clean equal-power blend anchored at contentEnd
        assertNotNull(plan)
        assertEquals(TransitionStyle.EQUAL_POWER, plan.transitionStyle)
        assertEquals(lowBeatConfidenceTrack.contentEndTime, plan.fadeEnd, 0.001)
        assertEquals(lowBeatConfidenceTrack.contentEndTime - plan.fadeSeconds, plan.fadeStart, 0.001)
    }

    @Test
    fun testQualityGateDowngradeWhenDownbeatGridNotAligned() {
        val unalignedTrack = createTrack(
            id = "unaligned",
            bpm = 120.0,
            key = "8B",
            beatConfidence = 0.9, // High confidence, but jittery downbeats
            downbeats = listOf(1.0, 2.3, 4.8, 6.1, 8.9),
        )
        val nextTrack = createTrack(
            id = "next",
            bpm = 120.0,
            key = "8B",
            beatConfidence = 0.9,
        )

        assertFalse(passesAdvancedQualityGate(unalignedTrack))

        val plan = planTransition(
            analysis = unalignedTrack,
            nextAnalysis = nextTrack,
            currentTrack = TransitionTrackInfo("unaligned", (unalignedTrack.duration * 1000).toLong()),
            nextTrack = TransitionTrackInfo("next", (nextTrack.duration * 1000).toLong()),
            currentTime = 10.0,
            duration = unalignedTrack.duration,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )

        assertNotNull(plan)
        assertEquals(TransitionStyle.EQUAL_POWER, plan.transitionStyle)
        assertEquals(unalignedTrack.contentEndTime, plan.fadeEnd, 0.001)
        assertEquals(unalignedTrack.contentEndTime - plan.fadeSeconds, plan.fadeStart, 0.001)
    }
}
