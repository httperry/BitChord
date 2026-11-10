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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CamelotHarmonicsTest {

    @Test
    fun testStandardKeyMappingAndParsing() {
        // Major keys -> Ring B
        assertEquals("8B", CamelotHarmonics.parse("C Major")?.code)
        assertEquals("8B", CamelotHarmonics.parse("C major")?.code)
        assertEquals("8B", CamelotHarmonics.parse("C")?.code)
        assertEquals("8B", CamelotHarmonics.parse("8B")?.code)

        assertEquals("9B", CamelotHarmonics.parse("G Major")?.code)
        assertEquals("9B", CamelotHarmonics.parse("G")?.code)

        assertEquals("10B", CamelotHarmonics.parse("D Major")?.code)
        assertEquals("10B", CamelotHarmonics.parse("D")?.code)

        assertEquals("11B", CamelotHarmonics.parse("A Major")?.code)
        assertEquals("11B", CamelotHarmonics.parse("A")?.code)

        assertEquals("12B", CamelotHarmonics.parse("E Major")?.code)
        assertEquals("12B", CamelotHarmonics.parse("E")?.code)

        assertEquals("1B", CamelotHarmonics.parse("B Major")?.code)
        assertEquals("1B", CamelotHarmonics.parse("B")?.code)

        // Enharmonic pairs
        assertEquals("2B", CamelotHarmonics.parse("F# Major")?.code)
        assertEquals("2B", CamelotHarmonics.parse("Gb Major")?.code)
        assertEquals("2B", CamelotHarmonics.parse("F#")?.code)
        assertEquals("2B", CamelotHarmonics.parse("Gb")?.code)

        assertEquals("3B", CamelotHarmonics.parse("Db Major")?.code)
        assertEquals("3B", CamelotHarmonics.parse("C# Major")?.code)
        assertEquals("3B", CamelotHarmonics.parse("Db")?.code)
        assertEquals("3B", CamelotHarmonics.parse("C#")?.code)

        assertEquals("4B", CamelotHarmonics.parse("Ab Major")?.code)
        assertEquals("4B", CamelotHarmonics.parse("G# Major")?.code)

        assertEquals("5B", CamelotHarmonics.parse("Eb Major")?.code)
        assertEquals("5B", CamelotHarmonics.parse("D# Major")?.code)
        assertEquals("5B", CamelotHarmonics.parse("Eb")?.code)

        assertEquals("6B", CamelotHarmonics.parse("Bb Major")?.code)
        assertEquals("6B", CamelotHarmonics.parse("A# Major")?.code)
        assertEquals("6B", CamelotHarmonics.parse("Bb")?.code)

        assertEquals("7B", CamelotHarmonics.parse("F Major")?.code)
        assertEquals("7B", CamelotHarmonics.parse("F")?.code)

        // Minor keys -> Ring A
        assertEquals("8A", CamelotHarmonics.parse("A Minor")?.code)
        assertEquals("8A", CamelotHarmonics.parse("A minor")?.code)
        assertEquals("8A", CamelotHarmonics.parse("Am")?.code)
        assertEquals("8A", CamelotHarmonics.parse("8A")?.code)

        assertEquals("9A", CamelotHarmonics.parse("E Minor")?.code)
        assertEquals("9A", CamelotHarmonics.parse("Em")?.code)

        assertEquals("10A", CamelotHarmonics.parse("B Minor")?.code)
        assertEquals("10A", CamelotHarmonics.parse("Bm")?.code)

        assertEquals("11A", CamelotHarmonics.parse("F# Minor")?.code)
        assertEquals("11A", CamelotHarmonics.parse("F#m")?.code)
        assertEquals("11A", CamelotHarmonics.parse("Gb Minor")?.code)
        assertEquals("11A", CamelotHarmonics.parse("Gbm")?.code)

        assertEquals("12A", CamelotHarmonics.parse("C# Minor")?.code)
        assertEquals("12A", CamelotHarmonics.parse("C#m")?.code)
        assertEquals("12A", CamelotHarmonics.parse("Db Minor")?.code)
        assertEquals("12A", CamelotHarmonics.parse("Dbm")?.code)

        assertEquals("1A", CamelotHarmonics.parse("G# Minor")?.code)
        assertEquals("1A", CamelotHarmonics.parse("G#m")?.code)
        assertEquals("1A", CamelotHarmonics.parse("Ab Minor")?.code)
        assertEquals("1A", CamelotHarmonics.parse("Abm")?.code)

        assertEquals("2A", CamelotHarmonics.parse("D# Minor")?.code)
        assertEquals("2A", CamelotHarmonics.parse("D#m")?.code)
        assertEquals("2A", CamelotHarmonics.parse("Eb Minor")?.code)
        assertEquals("2A", CamelotHarmonics.parse("Ebm")?.code)

        assertEquals("3A", CamelotHarmonics.parse("A# Minor")?.code)
        assertEquals("3A", CamelotHarmonics.parse("A#m")?.code)
        assertEquals("3A", CamelotHarmonics.parse("Bb Minor")?.code)
        assertEquals("3A", CamelotHarmonics.parse("Bbm")?.code)

        assertEquals("4A", CamelotHarmonics.parse("F Minor")?.code)
        assertEquals("4A", CamelotHarmonics.parse("Fm")?.code)

        assertEquals("5A", CamelotHarmonics.parse("C Minor")?.code)
        assertEquals("5A", CamelotHarmonics.parse("Cm")?.code)

        assertEquals("6A", CamelotHarmonics.parse("G Minor")?.code)
        assertEquals("6A", CamelotHarmonics.parse("Gm")?.code)

        assertEquals("7A", CamelotHarmonics.parse("D Minor")?.code)
        assertEquals("7A", CamelotHarmonics.parse("Dm")?.code)

        // Unicode accidental representations
        assertEquals("2B", CamelotHarmonics.parse("F♯ Major")?.code)
        assertEquals("2B", CamelotHarmonics.parse("F♯ major")?.code)
        assertEquals("11A", CamelotHarmonics.parse("F♯ minor")?.code)
        assertEquals("11A", CamelotHarmonics.parse("F♯m")?.code)
        assertEquals("5B", CamelotHarmonics.parse("E♭ Major")?.code)
        assertEquals("3A", CamelotHarmonics.parse("B♭ Minor")?.code)
        assertEquals("3A", CamelotHarmonics.parse("B♭m")?.code)

        // Camelot variations
        assertEquals("8A", CamelotHarmonics.parse("8a")?.code)
        assertEquals("8B", CamelotHarmonics.parse("8b")?.code)
        assertEquals("8A", CamelotHarmonics.parse("08A")?.code)
        assertEquals("12B", CamelotHarmonics.parse("12b")?.code)
        assertEquals("1A", CamelotHarmonics.parse(" 1A ")?.code)
        assertEquals("1B", CamelotHarmonics.parse(" 1 b ")?.code)

        // Invalid keys
        assertNull(CamelotHarmonics.parse(""))
        assertNull(CamelotHarmonics.parse("   "))
        assertNull(CamelotHarmonics.parse("invalid"))
        assertNull(CamelotHarmonics.parse("13A"))
        assertNull(CamelotHarmonics.parse("0A"))
        assertNull(CamelotHarmonics.parse("H Major"))
        assertEquals(CamelotHarmonics.KEY_ID_UNKNOWN, CamelotHarmonics.parseKeyId(null))
        assertEquals(CamelotHarmonics.KEY_ID_UNKNOWN, CamelotHarmonics.parseKeyId(""))
    }

    @Test
    fun testWheelDistances() {
        val cMaj = CamelotHarmonics.parse("8B")!!
        val aMin = CamelotHarmonics.parse("8A")!!
        val gMaj = CamelotHarmonics.parse("9B")!!
        val fMaj = CamelotHarmonics.parse("7B")!!
        val dMaj = CamelotHarmonics.parse("10B")!!
        val bbMaj = CamelotHarmonics.parse("6B")!!
        val fSharpMaj = CamelotHarmonics.parse("2B")!!
        val bMaj = CamelotHarmonics.parse("1B")!!
        val dbMaj = CamelotHarmonics.parse("3B")!!

        // Distance 0 (same wheel position)
        assertEquals(0, CamelotHarmonics.wheelDistance(cMaj, cMaj))
        assertEquals(0, CamelotHarmonics.wheelDistance(cMaj, aMin)) // Relative key: same wheel position

        // Distance 1 (adjacent keys)
        assertEquals(1, CamelotHarmonics.wheelDistance(cMaj, gMaj))
        assertEquals(1, CamelotHarmonics.wheelDistance(cMaj, fMaj))

        // Distance 2 (whole step shift)
        assertEquals(2, CamelotHarmonics.wheelDistance(cMaj, dMaj))
        assertEquals(2, CamelotHarmonics.wheelDistance(cMaj, bbMaj))

        // Distance 5 (semitone shift in same mode)
        assertEquals(5, CamelotHarmonics.wheelDistance(cMaj, bMaj))
        assertEquals(5, CamelotHarmonics.wheelDistance(cMaj, dbMaj))

        // Distance 6 (tritone shift: diametrically opposite on the wheel)
        assertEquals(6, CamelotHarmonics.wheelDistance(cMaj, fSharpMaj))

        // Wrap-around across 12 <-> 1
        val eMaj = CamelotHarmonics.parse("12B")!!
        assertEquals(1, CamelotHarmonics.wheelDistance(eMaj, bMaj))
        assertEquals(2, CamelotHarmonics.wheelDistance(eMaj, fSharpMaj))
    }

    @Test
    fun testPitchClassDistances() {
        val cMaj = CamelotHarmonics.parse("8B")!! // pitch class 0
        val cSharpMaj = CamelotHarmonics.parse("3B")!! // pitch class 1
        val dMaj = CamelotHarmonics.parse("10B")!! // pitch class 2
        val ebMaj = CamelotHarmonics.parse("5B")!! // pitch class 3
        val eMaj = CamelotHarmonics.parse("12B")!! // pitch class 4
        val fMaj = CamelotHarmonics.parse("7B")!! // pitch class 5
        val fSharpMaj = CamelotHarmonics.parse("2B")!! // pitch class 6
        val bMaj = CamelotHarmonics.parse("1B")!! // pitch class 11

        assertEquals(0, CamelotHarmonics.pitchClassDistance(cMaj, cMaj))
        assertEquals(1, CamelotHarmonics.pitchClassDistance(cMaj, cSharpMaj)) // Half step
        assertEquals(1, CamelotHarmonics.pitchClassDistance(cMaj, bMaj)) // Half step
        assertEquals(2, CamelotHarmonics.pitchClassDistance(cMaj, dMaj)) // Whole step
        assertEquals(3, CamelotHarmonics.pitchClassDistance(cMaj, ebMaj)) // Minor third
        assertEquals(4, CamelotHarmonics.pitchClassDistance(cMaj, eMaj)) // Major third
        assertEquals(5, CamelotHarmonics.pitchClassDistance(cMaj, fMaj)) // Perfect fourth
        assertEquals(6, CamelotHarmonics.pitchClassDistance(cMaj, fSharpMaj)) // Tritone
    }

    @Test
    fun testHarmonicCompatibilityScores() {
        val k8A = CamelotHarmonics.parse("8A")!! // A Minor
        val k8B = CamelotHarmonics.parse("8B")!! // C Major
        val k7A = CamelotHarmonics.parse("7A")!! // D Minor
        val k9A = CamelotHarmonics.parse("9A")!! // E Minor
        val k7B = CamelotHarmonics.parse("7B")!! // F Major
        val k9B = CamelotHarmonics.parse("9B")!! // G Major
        val k10A = CamelotHarmonics.parse("10A")!! // B Minor
        val k6A = CamelotHarmonics.parse("6A")!! // G Minor
        val k10B = CamelotHarmonics.parse("10B")!! // D Major
        val k6B = CamelotHarmonics.parse("6B")!! // Bb Major
        val k5A = CamelotHarmonics.parse("5A")!! // C Minor (Parallel to 8B)
        val k11B = CamelotHarmonics.parse("11B")!! // A Major (Parallel to 8A)

        // 1.0 for same key
        assertEquals(1.0, CamelotHarmonics.harmonicScore(k8A, k8A), 1e-6)
        assertEquals(1.0, CamelotHarmonics.harmonicScore(k8B, k8B), 1e-6)

        // 0.9 for relative key
        assertEquals(0.9, CamelotHarmonics.harmonicScore(k8A, k8B), 1e-6)
        assertEquals(0.9, CamelotHarmonics.harmonicScore(k8B, k8A), 1e-6)

        // 0.9 for adjacent keys (same mode, +/-1 step on wheel)
        assertEquals(0.9, CamelotHarmonics.harmonicScore(k8A, k7A), 1e-6)
        assertEquals(0.9, CamelotHarmonics.harmonicScore(k8A, k9A), 1e-6)
        assertEquals(0.9, CamelotHarmonics.harmonicScore(k8B, k7B), 1e-6)
        assertEquals(0.9, CamelotHarmonics.harmonicScore(k8B, k9B), 1e-6)

        // 0.7 for energy boost (+2/-2 whole step shift, same mode)
        assertEquals(0.7, CamelotHarmonics.harmonicScore(k8A, k10A), 1e-6)
        assertEquals(0.7, CamelotHarmonics.harmonicScore(k8A, k6A), 1e-6)
        assertEquals(0.7, CamelotHarmonics.harmonicScore(k8B, k10B), 1e-6)
        assertEquals(0.7, CamelotHarmonics.harmonicScore(k8B, k6B), 1e-6)

        // 0.7 for diagonal adjacent (+/-1 step with mode change)
        assertEquals(0.7, CamelotHarmonics.harmonicScore(k8A, k7B), 1e-6) // Am -> F Major
        assertEquals(0.7, CamelotHarmonics.harmonicScore(k8A, k9B), 1e-6) // Am -> G Major
        assertEquals(0.7, CamelotHarmonics.harmonicScore(k8B, k7A), 1e-6) // C Major -> Dm
        assertEquals(0.7, CamelotHarmonics.harmonicScore(k8B, k9A), 1e-6) // C Major -> Em

        // 0.6 for parallel key (same tonic, mode change: 8B C Major <-> 5A C Minor)
        assertEquals(0.6, CamelotHarmonics.harmonicScore(k8B, k5A), 1e-6)
        assertEquals(0.6, CamelotHarmonics.harmonicScore(k8A, k11B), 1e-6)

        // 0.2 for semitone (+/-1 half-step) dissonant clashes
        val k3B = CamelotHarmonics.parse("3B")!! // Db Major (half-step above C)
        val k1B = CamelotHarmonics.parse("1B")!! // B Major (half-step below C)
        val k12A = CamelotHarmonics.parse("12A")!! // C# Minor (half-step above C)
        val k4B = CamelotHarmonics.parse("4B")!! // Ab Major (half-step below A)

        assertEquals(0.2, CamelotHarmonics.harmonicScore(k8B, k3B), 1e-6)
        assertEquals(0.2, CamelotHarmonics.harmonicScore(k8B, k1B), 1e-6)
        assertEquals(0.2, CamelotHarmonics.harmonicScore(k8B, k12A), 1e-6)
        assertEquals(0.2, CamelotHarmonics.harmonicScore(k8A, k4B), 1e-6)

        // 0.2 for tritone (6 semitones) clashes
        val k2B = CamelotHarmonics.parse("2B")!! // F# Major (tritone from C)
        val k5B = CamelotHarmonics.parse("5B")!! // Eb Major (tritone from A)
        val k11A = CamelotHarmonics.parse("11A")!! // F# Minor (tritone from C)
        val k2A = CamelotHarmonics.parse("2A")!! // D# Minor (tritone from A)

        assertEquals(0.2, CamelotHarmonics.harmonicScore(k8B, k2B), 1e-6)
        assertEquals(0.2, CamelotHarmonics.harmonicScore(k8A, k5B), 1e-6)
        assertEquals(0.2, CamelotHarmonics.harmonicScore(k8B, k11A), 1e-6)
        assertEquals(0.2, CamelotHarmonics.harmonicScore(k8A, k2A), 1e-6)

        // Intermediate distance-based falloffs
        val k11B_from_8B = CamelotHarmonics.harmonicScore(k8B, CamelotHarmonics.parse("11B")!!) // 3 steps: minor third
        assertEquals(0.5, k11B_from_8B, 1e-6)

        val k12B_from_8B = CamelotHarmonics.harmonicScore(k8B, CamelotHarmonics.parse("12B")!!) // 4 steps: major third
        assertEquals(0.4, k12B_from_8B, 1e-6)
    }

    @Test
    fun testClashDetection() {
        val cMaj = CamelotHarmonics.parse("8B")!!
        val aMin = CamelotHarmonics.parse("8A")!!
        val gMaj = CamelotHarmonics.parse("9B")!!
        val dMaj = CamelotHarmonics.parse("10B")!!
        val fSharpMaj = CamelotHarmonics.parse("2B")!! // Tritone clash with C Major
        val dbMaj = CamelotHarmonics.parse("3B")!! // Semitone clash with C Major
        val bMaj = CamelotHarmonics.parse("1B")!! // Semitone clash with C Major
        val cSharpMin = CamelotHarmonics.parse("12A")!! // Semitone clash with C Major

        // Compatible keys are not clashes
        assertFalse(CamelotHarmonics.isClash(cMaj, cMaj))
        assertFalse(CamelotHarmonics.isClash(cMaj, aMin))
        assertFalse(CamelotHarmonics.isClash(cMaj, gMaj))
        assertFalse(CamelotHarmonics.isClash(cMaj, dMaj))

        // Semitone and tritone collisions are clashes
        assertTrue(CamelotHarmonics.isClash(cMaj, fSharpMaj))
        assertTrue(CamelotHarmonics.isClash(cMaj, dbMaj))
        assertTrue(CamelotHarmonics.isClash(cMaj, bMaj))
        assertTrue(CamelotHarmonics.isClash(cMaj, cSharpMin))

        // Direct string clash check
        assertTrue(CamelotHarmonics.isClash("C major", "F♯ major"))
        assertTrue(CamelotHarmonics.isClash("8B", "2B"))
        assertTrue(CamelotHarmonics.isClash("8B", "3B"))
        assertFalse(CamelotHarmonics.isClash("8B", "9B"))
    }

    @Test
    fun testHarmonicCompatibilityThreshold() {
        val cMaj = "8B"
        val gMaj = "9B"
        val aMin = "8A"
        val dMaj = "10B"
        val em = "9A"
        val fSharpMaj = "2B"
        val dbMaj = "3B"

        assertTrue(CamelotHarmonics.isHarmonicallyCompatible(cMaj, cMaj))
        assertTrue(CamelotHarmonics.isHarmonicallyCompatible(cMaj, gMaj))
        assertTrue(CamelotHarmonics.isHarmonicallyCompatible(cMaj, aMin))
        assertTrue(CamelotHarmonics.isHarmonicallyCompatible(cMaj, dMaj))
        assertTrue(CamelotHarmonics.isHarmonicallyCompatible(cMaj, em))

        assertFalse(CamelotHarmonics.isHarmonicallyCompatible(cMaj, fSharpMaj))
        assertFalse(CamelotHarmonics.isHarmonicallyCompatible(cMaj, dbMaj))
    }

    @Test
    fun testConfidenceWeightedHarmonicScore() {
        val cMaj = "C Major"
        val fSharpMaj = "F# Major"

        // Confident clash: weighted score close to raw clash score (0.2)
        val confidentClash = CamelotHarmonics.confidenceWeightedHarmonicScore(
            keyA = cMaj,
            confidenceA = 0.9,
            keyB = fSharpMaj,
            confidenceB = 0.9,
        )
        assertTrue(confidentClash < 0.3, "Confident clash should be heavily penalized: $confidentClash")

        // Unconfident clash: weighted score close to neutral baseline (0.5)
        val unconfidentClash = CamelotHarmonics.confidenceWeightedHarmonicScore(
            keyA = cMaj,
            confidenceA = 0.1,
            keyB = fSharpMaj,
            confidenceB = 0.1,
        )
        assertTrue(unconfidentClash > 0.45 && unconfidentClash < 0.55, "Unconfident clash should stay near neutral: $unconfidentClash")

        // Confident match: weighted score high (> 0.85)
        val confidentMatch = CamelotHarmonics.confidenceWeightedHarmonicScore(
            keyA = cMaj,
            confidenceA = 0.95,
            keyB = "8B",
            confidenceB = 0.95,
        )
        assertTrue(confidentMatch > 0.9, "Confident match should be high: $confidentMatch")

        // Missing keys answer neutral baseline
        assertEquals(0.5, CamelotHarmonics.confidenceWeightedHarmonicScore("", 0.9, "8B", 0.9))
        assertEquals(0.5, CamelotHarmonics.confidenceWeightedHarmonicScore("8B", 0.9, null, 0.9))
    }

    @Test
    fun testTrackAnalysisHarmonicCompatibility() {
        val trackA = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "a",
            bpm = 120.0,
            key = "C major",
            keyConfidence = 0.9,
        )
        val trackB = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "b",
            bpm = 120.0,
            key = "G major",
            keyConfidence = 0.85,
        )
        val trackClash = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "clash",
            bpm = 120.0,
            key = "F♯ major",
            keyConfidence = 0.9,
        )
        val trackNoKey = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "nokey",
            bpm = 120.0,
            key = "",
            keyConfidence = 0.0,
        )

        assertTrue(CamelotHarmonics.isHarmonicallyCompatible(trackA, trackB))
        assertFalse(CamelotHarmonics.isHarmonicallyCompatible(trackA, trackClash))
        assertTrue(CamelotHarmonics.isClash(trackA, trackClash))
        assertFalse(CamelotHarmonics.isClash(trackA, trackB))

        // Missing key is not treated as a clash
        assertFalse(CamelotHarmonics.isClash(trackA, trackNoKey))
        assertFalse(CamelotHarmonics.isHarmonicallyCompatible(trackA, trackNoKey))
    }

    @Test
    fun testZeroAllocationAndSingletons() {
        // Verify all 24 keys exist and are singletons
        assertEquals(24, CamelotHarmonics.ALL_KEYS.size)
        for (id in 0 until 24) {
            val key = CamelotHarmonics.ALL_KEYS[id]
            assertEquals(id, key.id)
            assertEquals(key, CamelotHarmonics.fromId(id))
            assertNotNull(key.standardName)
            assertTrue(key.pitchClass in 0..11)
        }

        // Relative key helper
        val k8A = CamelotHarmonics.parse("8A")!!
        val k8B = CamelotHarmonics.parse("8B")!!
        assertEquals(k8B, CamelotHarmonics.relativeKey(k8A))
        assertEquals(k8A, CamelotHarmonics.relativeKey(k8B))

        // Adjacent keys helper
        val (prev, next) = CamelotHarmonics.adjacentKeys(k8A)
        assertEquals("7A", prev.code)
        assertEquals("9A", next.code)

        // Rapid sweep over all 576 pairs evaluates with zero exceptions
        for (i in 0 until 24) {
            for (j in 0 until 24) {
                val score = CamelotHarmonics.harmonicScore(i, j)
                assertTrue(score in 0.0..1.0)
                val dist = CamelotHarmonics.wheelDistance(i, j)
                assertTrue(dist in 0..6)
                val semitones = CamelotHarmonics.pitchClassDistance(i, j)
                assertTrue(semitones in 0..6)
            }
        }
    }
}
