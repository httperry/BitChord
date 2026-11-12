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
import kotlin.math.min

/**
 * Mode on the Camelot wheel:
 * - [A] = Minor
 * - [B] = Major
 */
enum class CamelotMode {
    A, // Minor
    B; // Major

    companion object {
        fun fromChar(char: Char): CamelotMode? = when (char) {
            'A', 'a' -> A
            'B', 'b' -> B
            else -> null
        }
    }
}

/**
 * A coordinate on the Camelot wheel (1A - 12B).
 *
 * All 24 keys are pre-allocated as singletons to ensure zero-allocation lookups.
 */
data class CamelotKey(
    val number: Int,
    val mode: CamelotMode,
) {
    init {
        require(number in 1..12) { "Camelot number must be in 1..12, was $number" }
    }

    /** 0..23 integer identifier: 0..11 for 1A..12A (Minor), 12..23 for 1B..12B (Major). */
    val id: Int get() = (number - 1) + if (mode == CamelotMode.B) 12 else 0

    val isMajor: Boolean get() = mode == CamelotMode.B
    val isMinor: Boolean get() = mode == CamelotMode.A

    /** Tonic pitch class: 0 = C, 1 = C#/Db, ..., 11 = B. */
    val pitchClass: Int get() = CamelotHarmonics.tonicPitchClass(id)

    /** Standard Camelot notation (e.g. "8A", "8B"). */
    val code: String get() = "$number${mode.name}"

    /** Canonical standard musical key name (e.g. "A Minor", "C Major"). */
    val standardName: String get() = CamelotHarmonics.standardKeyName(id)

    override fun toString(): String = code
}

/**
 * High-performance, zero-allocation Camelot Wheel harmonic key compatibility engine.
 *
 * Uses precomputed 24x24 O(1) lookup tables for all distances, compatibility scores,
 * and clash detections, ensuring zero object allocation and zero GC pressure during
 * candidate ranking sweeps.
 */
object CamelotHarmonics {

    const val KEY_ID_UNKNOWN = -1
    const val TOTAL_KEYS = 24

    /** Pre-allocated singleton array for all 24 Camelot keys. */
    val ALL_KEYS: Array<CamelotKey> = Array(TOTAL_KEYS) { id ->
        val number = (id % 12) + 1
        val mode = if (id >= 12) CamelotMode.B else CamelotMode.A
        CamelotKey(number, mode)
    }

    /**
     * Pitch class (0 = C, 1 = C#/Db, ..., 11 = B) for each key ID (0..23).
     *
     * 1A - 12A (Minor):
     * 1A (G#m)=8, 2A (D#m)=3, 3A (A#m)=10, 4A (Fm)=5, 5A (Cm)=0, 6A (Gm)=7,
     * 7A (Dm)=2, 8A (Am)=9, 9A (Em)=4, 10A (Bm)=11, 11A (F#m)=6, 12A (C#m)=1
     *
     * 1B - 12B (Major):
     * 1B (B)=11, 2B (F#)=6, 3B (Db)=1, 4B (Ab)=8, 5B (Eb)=3, 6B (Bb)=10,
     * 7B (F)=5, 8B (C)=0, 9B (G)=7, 10B (D)=2, 11B (A)=9, 12B (E)=4
     */
    private val TONIC_PITCH_CLASSES = intArrayOf(
        8, 3, 10, 5, 0, 7, 2, 9, 4, 11, 6, 1,  // 1A - 12A
        11, 6, 1, 8, 3, 10, 5, 0, 7, 2, 9, 4   // 1B - 12B
    )

    private val STANDARD_KEY_NAMES = arrayOf(
        "G# Minor", "D# Minor", "A# Minor", "F Minor", "C Minor", "G Minor",
        "D Minor", "A Minor", "E Minor", "B Minor", "F# Minor", "C# Minor", // 1A - 12A
        "B Major", "F# Major", "Db Major", "Ab Major", "Eb Major", "Bb Major",
        "F Major", "C Major", "G Major", "D Major", "A Major", "E Major"   // 1B - 12B
    )

    // Precomputed 24x24 lookup tables (576 elements each)
    private val WHEEL_DIST_TABLE: IntArray
    private val SEMITONE_DIST_TABLE: IntArray
    private val SCORE_TABLE: DoubleArray
    private val CLASH_TABLE: BooleanArray
    private val COMPATIBLE_TABLE: BooleanArray

    init {
        val size = TOTAL_KEYS * TOTAL_KEYS
        WHEEL_DIST_TABLE = IntArray(size)
        SEMITONE_DIST_TABLE = IntArray(size)
        SCORE_TABLE = DoubleArray(size)
        CLASH_TABLE = BooleanArray(size)
        COMPATIBLE_TABLE = BooleanArray(size)

        for (idA in 0 until TOTAL_KEYS) {
            val numA = (idA % 12) + 1
            val isMajorA = idA >= 12
            val pitchA = TONIC_PITCH_CLASSES[idA]

            for (idB in 0 until TOTAL_KEYS) {
                val idx = idA * TOTAL_KEYS + idB
                val numB = (idB % 12) + 1
                val isMajorB = idB >= 12
                val pitchB = TONIC_PITCH_CLASSES[idB]

                // Circular distance on the 12-hour Camelot wheel: 0..6
                val rawDiff = abs(numA - numB)
                val wheelDist = min(rawDiff, 12 - rawDiff)
                WHEEL_DIST_TABLE[idx] = wheelDist

                // Shortest semitone distance between tonics: 0..6
                val pitchDiff = abs(pitchA - pitchB)
                val semitones = min(pitchDiff, 12 - pitchDiff)
                SEMITONE_DIST_TABLE[idx] = semitones

                // Determine harmonic compatibility score
                val score = computeHarmonicScore(idA, idB, isMajorA, isMajorB, wheelDist, semitones)
                SCORE_TABLE[idx] = score

                // Dissonant clash: semitone (+/-1 half-step) or tritone (6 semitones)
                val clash = (semitones == 1 || semitones == 6 || (isMajorA == isMajorB && wheelDist == 5) || wheelDist == 6)
                CLASH_TABLE[idx] = clash

                // Standard harmonic mixing threshold (same, relative, adjacent, energy boost +/-2, diagonal +/-1)
                COMPATIBLE_TABLE[idx] = score >= 0.65
            }
        }
    }

    private fun computeHarmonicScore(
        idA: Int,
        idB: Int,
        isMajorA: Boolean,
        isMajorB: Boolean,
        wheelDist: Int,
        semitones: Int,
    ): Double {
        if (idA == idB) return 1.0

        // Dissonant clash checks:
        // Semitone (+/-1 half-step) or tritone (6 semitones)
        if (semitones == 1 || semitones == 6) {
            return 0.2
        }

        return if (isMajorA == isMajorB) {
            // Same mode
            when (wheelDist) {
                0 -> 1.0 // Same key (handled above)
                1 -> 0.9 // Adjacent key (+/- 1 step on the wheel, circle of fifths)
                2 -> 0.7 // Energy boost +2/-2 (whole step shift: 2 semitones)
                3 -> 0.5 // Minor third shift (3 semitones)
                4 -> 0.4 // Major third shift (4 semitones)
                5 -> 0.2 // Semitone clash (caught above, fallback 0.2)
                6 -> 0.2 // Tritone clash (caught above, fallback 0.2)
                else -> 0.3
            }
        } else {
            // Mode change (Major <-> Minor)
            when (wheelDist) {
                0 -> 0.9 // Relative key (e.g. 8A <-> 8B, same key signature)
                1 -> 0.7 // Diagonal adjacent (+/- 1 step with mode change, e.g. 8A -> 7B or 9B)
                2 -> 0.55 // Whole step shift with mode change
                3 -> {
                    // Parallel key (e.g. 8B C Major <-> 5A C Minor, or 8A A Minor <-> 11B A Major)
                    if (semitones == 0) 0.6 else 0.45
                }
                4 -> 0.4
                5 -> 0.3
                6 -> 0.2 // Tritone opposite across modes
                else -> 0.25
            }
        }
    }

    /** Precomputed mapping of canonical string representations to Key ID (0..23). */
    private val STRING_TO_ID: Map<String, Int> = buildMap {
        // Camelot notations: "1a".."12a", "1b".."12b", "01a".."12a", "01b".."12b"
        for (num in 1..12) {
            val idA = num - 1
            val idB = (num - 1) + 12
            put("${num}a", idA)
            put("${num}b", idB)
            put("${num} a", idA)
            put("${num} b", idB)
            if (num < 10) {
                put("0${num}a", idA)
                put("0${num}b", idB)
                put("0${num} a", idA)
                put("0${num} b", idB)
            }
        }

        // Standard key mapping definitions
        fun register(id: Int, roots: List<String>, isMinor: Boolean) {
            for (root in roots) {
                val r = root.lowercase()
                if (isMinor) {
                    put("${r}m", id)
                    put("${r}min", id)
                    put("${r}minor", id)
                    put("${r} minor", id)
                    put("${r} min", id)
                    put("${r}-minor", id)
                } else {
                    put(r, id) // Bare root note defaults to Major
                    put("${r}m", id) // Wait, don't overwrite if minor! Handled below.
                    put("${r}maj", id)
                    put("${r}major", id)
                    put("${r} major", id)
                    put("${r} maj", id)
                    put("${r}-major", id)
                }
            }
        }

        // Minor keys (0..11)
        register(0, listOf("g#", "ab"), isMinor = true)
        register(1, listOf("d#", "eb"), isMinor = true)
        register(2, listOf("a#", "bb"), isMinor = true)
        register(3, listOf("f"), isMinor = true)
        register(4, listOf("c", "b#"), isMinor = true)
        register(5, listOf("g"), isMinor = true)
        register(6, listOf("d"), isMinor = true)
        register(7, listOf("a"), isMinor = true)
        register(8, listOf("e", "fb"), isMinor = true)
        register(9, listOf("b", "cb"), isMinor = true)
        register(10, listOf("f#", "gb"), isMinor = true)
        register(11, listOf("c#", "db"), isMinor = true)

        // Major keys (12..23)
        register(12, listOf("b", "cb"), isMinor = false)
        register(13, listOf("f#", "gb"), isMinor = false)
        register(14, listOf("db", "c#"), isMinor = false)
        register(15, listOf("ab", "g#"), isMinor = false)
        register(16, listOf("eb", "d#"), isMinor = false)
        register(17, listOf("bb", "a#"), isMinor = false)
        register(18, listOf("f", "e#"), isMinor = false)
        register(19, listOf("c", "b#"), isMinor = false)
        register(20, listOf("g"), isMinor = false)
        register(21, listOf("d"), isMinor = false)
        register(22, listOf("a"), isMinor = false)
        register(23, listOf("e", "fb"), isMinor = false)

        // Re-ensure minor keys win for "m" suffixes (e.g. "am" -> 8A, "cm" -> 5A)
        val minorRoots = mapOf(
            "g#" to 0, "ab" to 0,
            "d#" to 1, "eb" to 1,
            "a#" to 2, "bb" to 2,
            "f" to 3,
            "c" to 4, "b#" to 4,
            "g" to 5,
            "d" to 6,
            "a" to 7,
            "e" to 8, "fb" to 8,
            "b" to 9, "cb" to 9,
            "f#" to 10, "gb" to 10,
            "c#" to 11, "db" to 11,
        )
        for ((root, id) in minorRoots) {
            put("${root}m", id)
            put("${root} min", id)
            put("${root} min.", id)
            put("${root} min", id)
            put("${root}minor", id)
            put("${root} minor", id)
            put("${root}-minor", id)
        }
    }

    /** Returns tonic pitch class (0..11) for key ID (0..23). */
    fun tonicPitchClass(id: Int): Int {
        if (id !in 0 until TOTAL_KEYS) return -1
        return TONIC_PITCH_CLASSES[id]
    }

    /** Returns canonical name (e.g. "C Major", "A Minor") for key ID (0..23). */
    fun standardKeyName(id: Int): String {
        if (id !in 0 until TOTAL_KEYS) return "Unknown"
        return STANDARD_KEY_NAMES[id]
    }

    /** Returns singleton [CamelotKey] for key ID (0..23) or null if invalid. */
    fun fromId(id: Int): CamelotKey? = if (id in 0 until TOTAL_KEYS) ALL_KEYS[id] else null

    /** Returns singleton [CamelotKey] for number (1..12) and mode. */
    fun key(number: Int, mode: CamelotMode): CamelotKey {
        require(number in 1..12) { "Number must be 1..12" }
        val id = (number - 1) + if (mode == CamelotMode.B) 12 else 0
        return ALL_KEYS[id]
    }

    /**
     * Parses any Camelot or standard musical key string into its primitive Key ID (0..23).
     *
     * Returns [KEY_ID_UNKNOWN] (-1) if blank or unrecognized.
     * Zero object allocation when string is already in canonical form.
     */
    fun parseKeyId(raw: String?): Int {
        if (raw.isNullOrBlank()) return KEY_ID_UNKNOWN
        val cleaned = raw.trim()
            .replace('♯', '#')
            .replace('♭', 'b')
            .lowercase()

        val exactMatch = STRING_TO_ID[cleaned]
        if (exactMatch != null) return exactMatch

        // Fallback dynamic parser for variations like "12 A", "C - Major", "F# (minor)"
        return dynamicParseKeyId(cleaned)
    }

    private fun dynamicParseKeyId(str: String): Int {
        // Camelot notation regex: e.g. "8a", "08b", "12a"
        if (str.length in 2..5) {
            val match = Regex("""^0?([1-9]|1[0-2])\s*([ab])$""").matchEntire(str)
            if (match != null) {
                val num = match.groupValues[1].toInt()
                val isMajor = match.groupValues[2].equals("b", ignoreCase = true)
                return (num - 1) + if (isMajor) 12 else 0
            }
        }

        // Standard musical key parser
        // Find root note
        val rootNotes = listOf(
            "c#", "db", "d#", "eb", "f#", "gb", "g#", "ab", "a#", "bb", "cb", "b#", "fb", "e#",
            "c", "d", "e", "f", "g", "a", "b"
        )
        for (root in rootNotes) {
            if (str.startsWith(root)) {
                val remainder = str.substring(root.length).trim().trim('-', '_', '(', ')')
                val isMinor = when {
                    remainder.startsWith("min") -> true
                    remainder.startsWith("minor") -> true
                    remainder.startsWith("m") && !remainder.startsWith("maj") -> true
                    else -> false
                }
                val candidateKey = "$root${if (isMinor) "m" else ""}"
                val resolvedId = STRING_TO_ID[candidateKey]
                if (resolvedId != null) return resolvedId
            }
        }

        return KEY_ID_UNKNOWN
    }

    /**
     * Parses a key string and returns the pre-allocated [CamelotKey] singleton, or null.
     */
    fun parse(raw: String?): CamelotKey? {
        val id = parseKeyId(raw)
        return if (id >= 0) ALL_KEYS[id] else null
    }

    /**
     * Creates a [CamelotKey] from pitch class (0..11) and mode.
     */
    fun fromPitchClass(pitchClass: Int, isMinor: Boolean): CamelotKey {
        val pc = (pitchClass % 12 + 12) % 12
        val startId = if (isMinor) 0 else 12
        for (id in startId until startId + 12) {
            if (TONIC_PITCH_CLASSES[id] == pc) {
                return ALL_KEYS[id]
            }
        }
        return ALL_KEYS[if (isMinor) 7 else 19] // Default A minor or C major
    }

    // ---------------------------------------------------------------------------
    // Ultra-Fast Zero-Allocation O(1) Compatibility & Scoring Primitives
    // ---------------------------------------------------------------------------

    /**
     * O(1) circular distance on the 12-hour Camelot wheel: 0..6.
     */
    fun wheelDistance(idA: Int, idB: Int): Int {
        if (idA !in 0 until TOTAL_KEYS || idB !in 0 until TOTAL_KEYS) return 6
        return WHEEL_DIST_TABLE[idA * TOTAL_KEYS + idB]
    }

    fun wheelDistance(keyA: CamelotKey, keyB: CamelotKey): Int =
        wheelDistance(keyA.id, keyB.id)

    /**
     * O(1) semitone distance between tonic pitch classes: 0..6.
     */
    fun pitchClassDistance(idA: Int, idB: Int): Int {
        if (idA !in 0 until TOTAL_KEYS || idB !in 0 until TOTAL_KEYS) return 6
        return SEMITONE_DIST_TABLE[idA * TOTAL_KEYS + idB]
    }

    fun pitchClassDistance(keyA: CamelotKey, keyB: CamelotKey): Int =
        pitchClassDistance(keyA.id, keyB.id)

    /**
     * O(1) check for dissonant key collision (semitone +/-1 half-step or tritone 6 semitones).
     */
    fun isClash(idA: Int, idB: Int): Boolean {
        if (idA !in 0 until TOTAL_KEYS || idB !in 0 until TOTAL_KEYS) return false
        return CLASH_TABLE[idA * TOTAL_KEYS + idB]
    }

    fun isClash(keyA: CamelotKey, keyB: CamelotKey): Boolean =
        isClash(keyA.id, keyB.id)

    fun isClash(keyA: String?, keyB: String?): Boolean {
        val idA = parseKeyId(keyA)
        val idB = parseKeyId(keyB)
        if (idA == KEY_ID_UNKNOWN || idB == KEY_ID_UNKNOWN) return false
        return isClash(idA, idB)
    }

    /**
     * Checks if both track analyses have trusted keys and produce a dissonant clash.
     */
    fun isClash(
        analysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        minConfidence: Double = 0.25,
    ): Boolean {
        if (analysis.key.isBlank() || nextAnalysis.key.isBlank()) return false
        if (analysis.keyConfidence < minConfidence || nextAnalysis.keyConfidence < minConfidence) return false
        val idA = parseKeyId(analysis.key)
        val idB = parseKeyId(nextAnalysis.key)
        if (idA == KEY_ID_UNKNOWN || idB == KEY_ID_UNKNOWN) return false
        return isClash(idA, idB)
    }

    /**
     * O(1) harmonic compatibility score (0.0 to 1.0):
     * - 1.0 for same key
     * - 0.9 for relative key or adjacent key (same mode, +/-1 step)
     * - 0.7 for energy boost (+2/-2 whole step shift) or diagonal adjacent (+/-1 with mode change)
     * - 0.2 for semitone or tritone dissonant clashes
     * - Intermediate distance falloffs for other musical intervals.
     */
    fun harmonicScore(idA: Int, idB: Int): Double {
        if (idA !in 0 until TOTAL_KEYS || idB !in 0 until TOTAL_KEYS) return 0.5
        return SCORE_TABLE[idA * TOTAL_KEYS + idB]
    }

    fun harmonicScore(keyA: CamelotKey, keyB: CamelotKey): Double =
        harmonicScore(keyA.id, keyB.id)

    fun harmonicScore(rawA: String?, rawB: String?): Double {
        val idA = parseKeyId(rawA)
        val idB = parseKeyId(rawB)
        if (idA == KEY_ID_UNKNOWN || idB == KEY_ID_UNKNOWN) return 0.5
        return harmonicScore(idA, idB)
    }

    /**
     * O(1) harmonic compatibility score (0.0 to 1.0) between two keys.
     * Evaluates compatibility on the Camelot wheel:
     * - 1.0 for same key (e.g. 8A -> 8A)
     * - 0.9 for relative key (e.g. 8A -> 8B)
     * - 0.9 for adjacent key (e.g. 8A -> 7A, 9A)
     * - 0.8 for energy-boost key (+/- 2 whole step shift, or diagonal adjacent +/- 1)
     * - 0.2 for discordant key clashes (semitone +/- 1 half-step or tritone 6 semitones)
     */
    fun scoreCompatibility(idA: Int, idB: Int): Double {
        if (idA !in 0 until TOTAL_KEYS || idB !in 0 until TOTAL_KEYS) return 0.5
        val raw = harmonicScore(idA, idB)
        if (isClash(idA, idB)) return 0.2
        val wheelDist = wheelDistance(idA, idB)
        val isSameMode = (idA < 12) == (idB < 12)
        if (isSameMode && wheelDist == 2) return 0.8
        if (!isSameMode && wheelDist == 1) return 0.8
        return raw
    }

    fun scoreCompatibility(currentKey: String?, nextKey: String?): Double {
        val idA = parseKeyId(currentKey)
        val idB = parseKeyId(nextKey)
        if (idA == KEY_ID_UNKNOWN || idB == KEY_ID_UNKNOWN) return 0.5
        return scoreCompatibility(idA, idB)
    }

    fun scoreCompatibility(currentKey: CamelotKey, nextKey: CamelotKey): Double =
        scoreCompatibility(currentKey.id, nextKey.id)

    /**
     * Returns true if two keys meet the harmonic compatibility threshold (default 0.65).
     */
    fun isHarmonicallyCompatible(idA: Int, idB: Int, minScore: Double = 0.65): Boolean {
        if (idA !in 0 until TOTAL_KEYS || idB !in 0 until TOTAL_KEYS) return false
        return if (minScore == 0.65) {
            COMPATIBLE_TABLE[idA * TOTAL_KEYS + idB]
        } else {
            SCORE_TABLE[idA * TOTAL_KEYS + idB] >= minScore
        }
    }

    fun isHarmonicallyCompatible(keyA: CamelotKey, keyB: CamelotKey, minScore: Double = 0.65): Boolean =
        isHarmonicallyCompatible(keyA.id, keyB.id, minScore)

    fun isHarmonicallyCompatible(keyA: String?, keyB: String?, minScore: Double = 0.65): Boolean {
        val idA = parseKeyId(keyA)
        val idB = parseKeyId(keyB)
        if (idA == KEY_ID_UNKNOWN || idB == KEY_ID_UNKNOWN) return false
        return isHarmonicallyCompatible(idA, idB, minScore)
    }

    fun isHarmonicallyCompatible(
        analysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        minScore: Double = 0.65,
        minConfidence: Double = 0.25,
    ): Boolean {
        if (analysis.key.isBlank() || nextAnalysis.key.isBlank()) return false
        if (analysis.keyConfidence < minConfidence || nextAnalysis.keyConfidence < minConfidence) return false
        val idA = parseKeyId(analysis.key)
        val idB = parseKeyId(nextAnalysis.key)
        if (idA == KEY_ID_UNKNOWN || idB == KEY_ID_UNKNOWN) return false
        return isHarmonicallyCompatible(idA, idB, minScore)
    }

    /**
     * Confidence-weighted harmonic score using individual key detection confidences.
     *
     * Falls back smoothly toward [neutralBaseline] (default 0.5) when certainty is low,
     * ensuring unconfident predictions do not falsely penalize or reward candidate mixes.
     */
    fun confidenceWeightedHarmonicScore(
        keyA: String?,
        confidenceA: Double,
        keyB: String?,
        confidenceB: Double,
        neutralBaseline: Double = 0.5,
    ): Double {
        val idA = parseKeyId(keyA)
        val idB = parseKeyId(keyB)
        if (idA == KEY_ID_UNKNOWN || idB == KEY_ID_UNKNOWN) return neutralBaseline
        if (!confidenceA.isFinite() || !confidenceB.isFinite() || confidenceA <= 0.0 || confidenceB <= 0.0) {
            return neutralBaseline
        }
        val rawScore = harmonicScore(idA, idB)
        val jointConfidence = (confidenceA.coerceIn(0.0, 1.0) * confidenceB.coerceIn(0.0, 1.0))
        return neutralBaseline + jointConfidence * (rawScore - neutralBaseline)
    }

    fun confidenceWeightedHarmonicScore(
        analysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        neutralBaseline: Double = 0.5,
    ): Double = confidenceWeightedHarmonicScore(
        keyA = analysis.key,
        confidenceA = analysis.keyConfidence,
        keyB = nextAnalysis.key,
        confidenceB = nextAnalysis.keyConfidence,
        neutralBaseline = neutralBaseline,
    )

    /**
     * Extracts the trusted Key ID from a track analysis.
     */
    fun trustedKeyId(analysis: TrackAnalysis, minConfidence: Double = 0.25): Int {
        if (analysis.key.isBlank() || analysis.keyConfidence < minConfidence) return KEY_ID_UNKNOWN
        return parseKeyId(analysis.key)
    }

    /** Relative key (shares the same key signature; e.g. 8A <-> 8B). */
    fun relativeKey(key: CamelotKey): CamelotKey =
        ALL_KEYS[(key.number - 1) + if (key.mode == CamelotMode.A) 12 else 0]

    /** Adjacent keys on the wheel in the same mode (+/- 1 step). */
    fun adjacentKeys(key: CamelotKey): Pair<CamelotKey, CamelotKey> {
        val prevNum = if (key.number == 1) 12 else key.number - 1
        val nextNum = if (key.number == 12) 1 else key.number + 1
        val offset = if (key.mode == CamelotMode.B) 12 else 0
        return ALL_KEYS[(prevNum - 1) + offset] to ALL_KEYS[(nextNum - 1) + offset]
    }
}
