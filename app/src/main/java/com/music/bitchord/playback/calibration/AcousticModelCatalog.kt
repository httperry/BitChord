package com.music.bitchord.playback.calibration

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Type of acoustic transducer device.
 */
@Serializable
enum class AcousticDeviceType(val displayName: String) {
    IEM("In-Ear Monitor"),
    OVER_EAR("Over-Ear"),
    TWS("True Wireless Stereo")
}

/**
 * Complete acoustic model specification with multi-target calibration profiles.
 *
 * @property id Unique persistent identifier (e.g., "moondrop_blessing_3").
 * @property name Model name (e.g., "Blessing 3").
 * @property brand Manufacturer / brand name (e.g., "Moondrop").
 * @property type Physical form factor / device type (IEM, OVER_EAR, TWS).
 * @property targetProfiles Map of target curves to their calibrated [AutoEqProfile].
 * @property defaultTarget Default target curve for this transducer type.
 */
@Serializable
data class AcousticModel(
    val id: String,
    val name: String,
    val brand: String,
    val type: AcousticDeviceType,
    val targetProfiles: Map<CalibrationTarget, AutoEqProfile>,
    val defaultTarget: CalibrationTarget = when (type) {
        AcousticDeviceType.OVER_EAR -> CalibrationTarget.HARMAN_OVER_EAR_2018
        AcousticDeviceType.IEM, AcousticDeviceType.TWS -> CalibrationTarget.HARMAN_IN_EAR_2019
    }
) {
    /**
     * Active profile based on [defaultTarget], or the first available profile.
     */
    val activeProfile: AutoEqProfile
        get() = targetProfiles[defaultTarget]
            ?: targetProfiles.values.firstOrNull()
            ?: AutoEqProfile(defaultTarget, 0f, emptyList(), List(10) { 0f })

    /** Pre-amp attenuation in dB for the active profile. */
    val preampDb: Float get() = activeProfile.preampDb

    /** Parametric biquad bands for the active profile. */
    val biquadBands: List<BiquadBand> get() = activeProfile.biquadBands

    /** 10-band calibration offsets in dB for the active profile. */
    val bandOffsets10: List<Float> get() = activeProfile.bandOffsets10

    /**
     * Returns the calibration profile for [target], falling back to [activeProfile] if not found.
     */
    fun profileFor(target: CalibrationTarget): AutoEqProfile =
        targetProfiles[target] ?: activeProfile

    /**
     * Returns the calibration profile for [target] or null if not mapped.
     */
    fun getProfile(target: CalibrationTarget): AutoEqProfile? =
        targetProfiles[target]
}

/**
 * Acoustic Model Catalog repository and AutoEQ profile registry.
 *
 * Provides query, prefix & fuzzy search, brand/type filtering, and JSON import/export.
 * Initialized with 65 production audiophile and consumer headphone/IEM profiles.
 */
object AcousticModelCatalog {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Volatile
    private var customModelsList: List<AcousticModel>? = null

    private val modelsList: List<AcousticModel>
        get() = customModelsList ?: BUILTIN_MODELS

    /**
     * Returns all acoustic models currently loaded in the catalog.
     */
    fun getModels(): List<AcousticModel> = modelsList

    private val ALIAS_MAP: Map<String, String> = mapOf(
        "moondrop_chu_2" to "moondrop_chu_ii",
        "tangzu_waner" to "tangzu_waner_sg",
        "tangzu_waner_se" to "tangzu_waner_sg",
        "sevenhz_zero" to "7hz_zero",
        "sevenhz_zero2" to "7hz_zero_2",
        "sevenhz_timeless" to "7hz_timeless",
        "sevenhz_legato" to "7hz_legato",
        "audiotechnica_m50x" to "audiotechnica_ath_m50x",
        "audiotechnica_r70x" to "audiotechnica_ath_r70x",
        "sony_ierm9" to "sony_ier_m9"
    )

    /**
     * Finds a model by its unique [id] (case-insensitive).
     */
    fun getModelById(id: String): AcousticModel? {
        val trimmed = id.trim()
        val resolvedId = ALIAS_MAP[trimmed.lowercase(Locale.ROOT)] ?: trimmed
        return modelsList.firstOrNull { it.id.equals(resolvedId, ignoreCase = true) }
    }

    /**
     * Returns a sorted list of unique brand names in the catalog.
     */
    fun getBrands(): List<String> =
        modelsList.map { it.brand }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    /**
     * Returns all models matching the specified [brand] name (case-insensitive).
     */
    fun getModelsByBrand(brand: String): List<AcousticModel> {
        val trimmed = brand.trim()
        return modelsList.filter { it.brand.equals(trimmed, ignoreCase = true) }
    }

    /**
     * Returns all models matching the specified [type].
     */
    fun getModelsByType(type: AcousticDeviceType): List<AcousticModel> =
        modelsList.filter { it.type == type }

    /**
     * Performs fuzzy and prefix search across model names, brands, and IDs with brand and type filtering.
     *
     * @param query Search query string.
     * @param brandFilter Optional brand filter.
     * @param typeFilter Optional device type filter.
     * @return Ranked list of matching acoustic models.
     */
    fun search(
        query: String,
        brandFilter: String? = null,
        typeFilter: AcousticDeviceType? = null
    ): List<AcousticModel> {
        val cleanBrandFilter = brandFilter?.trim()?.takeIf { it.isNotEmpty() }
        val baseCandidates = modelsList.filter { model ->
            (cleanBrandFilter == null || model.brand.equals(cleanBrandFilter, ignoreCase = true)) &&
                (typeFilter == null || model.type == typeFilter)
        }

        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return baseCandidates.sortedWith(
                compareBy<AcousticModel> { it.brand.lowercase(Locale.ROOT) }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
        }

        val cleanQuery = normalize(trimmedQuery)

        data class ScoredModel(val model: AcousticModel, val score: Int)

        return baseCandidates
            .mapNotNull { model ->
                val score = calculateMatchScore(cleanQuery, model)
                if (score > 0) ScoredModel(model, score) else null
            }
            .sortedWith(
                compareByDescending<ScoredModel> { it.score }
                    .thenBy { it.model.brand.lowercase(Locale.ROOT) }
                    .thenBy { it.model.name.lowercase(Locale.ROOT) }
            )
            .map { it.model }
    }

    /**
     * Loads models from a JSON string, validating the schema.
     */
    fun loadFromJson(jsonString: String): List<AcousticModel> =
        json.decodeFromString<List<AcousticModel>>(jsonString)

    /**
     * Serializes the catalog models into a formatted JSON string.
     */
    fun exportToJson(models: List<AcousticModel> = getModels()): String =
        json.encodeToString(models)

    /**
     * Reloads the in-memory catalog from a JSON string.
     */
    @Synchronized
    fun reloadFromJson(jsonString: String) {
        customModelsList = loadFromJson(jsonString)
    }

    /**
     * Resets the catalog to the built-in factory presets.
     */
    @Synchronized
    fun resetToDefault() {
        customModelsList = null
    }

    /**
     * Loads models from an input stream (e.g. from assets or file storage).
     */
    fun loadFromInputStream(inputStream: java.io.InputStream): List<AcousticModel> {
        val jsonContent = inputStream.bufferedReader().use { it.readText() }
        val loaded = loadFromJson(jsonContent)
        customModelsList = loaded
        return loaded
    }

    /**
     * Loads the catalog from an Android asset file (e.g. "acoustic_catalog.json").
     */
    fun loadFromAssets(context: Context, assetPath: String = "acoustic_catalog.json"): List<AcousticModel> =
        loadFromInputStream(context.assets.open(assetPath))

    private fun calculateMatchScore(cleanQuery: String, model: AcousticModel): Int {
        val cleanId = normalize(model.id)
        val cleanName = normalize(model.name)
        val cleanBrand = normalize(model.brand)
        val cleanFull = cleanBrand + cleanName

        if (cleanId == cleanQuery) return 1000
        if (cleanName == cleanQuery) return 850
        if (cleanFull == cleanQuery) return 800

        var score = 0

        if (cleanName.startsWith(cleanQuery)) {
            score = max(score, 600)
        } else if (cleanFull.startsWith(cleanQuery) || cleanBrand.startsWith(cleanQuery)) {
            score = max(score, 500)
        }

        val tokens = (model.brand + " " + model.name).split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotEmpty() }
        for (token in tokens) {
            val cleanToken = normalize(token)
            if (cleanToken == cleanQuery) {
                score = max(score, 550)
            } else if (cleanToken.startsWith(cleanQuery)) {
                score = max(score, 450)
            }
        }

        if (cleanName.contains(cleanQuery)) {
            score = max(score, 350)
        } else if (cleanFull.contains(cleanQuery)) {
            score = max(score, 250)
        }

        if (cleanQuery.length >= 3) {
            val simName = stringSimilarity(cleanQuery, cleanName)
            if (simName >= 0.70f) {
                score = max(score, (simName * 300).toInt())
            }
            val simBrand = stringSimilarity(cleanQuery, cleanBrand)
            if (simBrand >= 0.70f) {
                score = max(score, (simBrand * 250).toInt())
            }
            for (token in tokens) {
                val cleanToken = normalize(token)
                if (cleanToken.length >= 3) {
                    val simToken = stringSimilarity(cleanQuery, cleanToken)
                    if (simToken >= 0.75f) {
                        score = max(score, (simToken * 280).toInt())
                    }
                }
            }
        }

        return score
    }

    private fun normalize(input: String): String {
        val lower = input.lowercase(Locale.ROOT)
        val sb = java.lang.StringBuilder(lower.length)
        for (i in 0 until lower.length) {
            val c = lower[i]
            if (c in 'a'..'z' || c in '0'..'9') {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun stringSimilarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0.0f
        val dist = levenshteinDistance(s1, s2)
        val maxLen = max(s1.length, s2.length)
        return 1.0f - (dist.toFloat() / maxLen.toFloat())
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            val c1 = s1[i - 1]
            for (j in 1..n) {
                val c2 = s2[j - 1]
                val cost = if (c1 == c2) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[n]
    }

    private val BUILTIN_MODELS: List<AcousticModel> by lazy {
        listOf(
            AcousticModel(
                id = "moondrop_chu",
                name = "Chu",
                brand = "Moondrop",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -4.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 4.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, -3.0f, 3.0f), BiquadBand(BiquadFilterType.PEAKING, 12000.0f, -2.0f, 2.0f)),
                        bandOffsets10 = listOf(3.9f, 2.9f, 0.6f, 0.0f, -0.0f, -0.0f, -0.1f, -0.5f, -1.3f, -0.9f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, -3.5f, 3.0f)),
                        bandOffsets10 = listOf(1.5f, 1.1f, 0.2f, 0.0f, -0.0f, -0.0f, -0.1f, -0.5f, -0.9f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "moondrop_chu_ii",
                name = "Chu II",
                brand = "Moondrop",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -1.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 4500.0f, -2.5f, 2.5f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -3.0f, 2.0f)),
                        bandOffsets10 = listOf(-0.0f, -0.2f, -0.8f, -1.2f, -0.3f, -0.1f, -0.2f, -2.2f, -3.2f, -0.3f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 4500.0f, -2.5f, 2.5f)),
                        bandOffsets10 = listOf(-2.0f, -1.7f, -0.6f, -0.0f, -0.0f, -0.0f, -0.1f, -1.9f, -0.2f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "moondrop_aria",
                name = "Aria",
                brand = "Moondrop",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 75.0f, 2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 250.0f, -1.0f, 1.2f), BiquadBand(BiquadFilterType.PEAKING, 6500.0f, -2.0f, 2.0f)),
                        bandOffsets10 = listOf(2.4f, 1.6f, 0.0f, -1.0f, -0.2f, -0.1f, -0.1f, -0.4f, -1.2f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -1.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 6500.0f, -2.5f, 2.0f)),
                        bandOffsets10 = listOf(-0.0f, -0.2f, -0.8f, -1.2f, -0.3f, -0.1f, -0.1f, -0.5f, -1.5f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "moondrop_kato",
                name = "Kato",
                brand = "Moondrop",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 85.0f, 2.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, -1.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, -2.5f, 2.5f)),
                        bandOffsets10 = listOf(2.0f, 1.5f, 0.3f, 0.0f, -0.0f, -0.1f, -0.4f, -1.0f, -0.9f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -1.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, -2.5f, 2.0f)),
                        bandOffsets10 = listOf(-0.0f, -0.1f, -0.5f, -0.8f, -0.2f, -0.1f, -0.1f, -0.7f, -1.1f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "moondrop_blessing_2",
                name = "Blessing 2",
                brand = "Moondrop",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -3.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 105.0f, 3.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 2.5f, 2.5f), BiquadBand(BiquadFilterType.PEAKING, 12000.0f, -2.5f, 2.0f)),
                        bandOffsets10 = listOf(3.5f, 3.1f, 1.2f, 0.1f, 0.0f, 0.0f, 0.0f, 0.4f, 0.1f, -1.0f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 1.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 2.0f, 2.5f)),
                        bandOffsets10 = listOf(1.0f, 0.7f, 0.1f, 0.0f, 0.0f, 0.0f, 0.0f, 0.4f, 0.6f, 0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "moondrop_blessing_3",
                name = "Blessing 3",
                brand = "Moondrop",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 90.0f, 1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3200.0f, -1.5f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 1.5f, 2.0f)),
                        bandOffsets10 = listOf(1.5f, 1.2f, 0.3f, 0.0f, -0.0f, -0.0f, -0.3f, -0.4f, 0.6f, 0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.4f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 3200.0f, -2.0f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 1.5f, 2.0f)),
                        bandOffsets10 = listOf(-0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.4f, -0.7f, 0.5f, 0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "moondrop_variations",
                name = "Variations",
                brand = "Moondrop",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, 1.5f, 1.2f), BiquadBand(BiquadFilterType.PEAKING, 3500.0f, -1.5f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, -2.0f, 2.5f)),
                        bandOffsets10 = listOf(0.0f, 0.1f, 0.6f, 1.2f, 0.2f, 0.0f, -0.2f, -1.4f, -1.5f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.5f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 200.0f, 1.5f, 1.2f), BiquadBand(BiquadFilterType.PEAKING, 3500.0f, -1.5f, 2.0f)),
                        bandOffsets10 = listOf(-2.5f, -2.1f, -0.1f, 1.1f, 0.2f, 0.0f, -0.2f, -1.2f, -0.1f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "moondrop_starfield",
                name = "Starfield",
                brand = "Moondrop",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 4000.0f, 1.5f, 1.8f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, -2.0f, 2.5f)),
                        bandOffsets10 = listOf(2.4f, 1.8f, 0.4f, 0.0f, 0.0f, 0.0f, 0.1f, 1.1f, -0.5f, -0.0f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -1.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 4000.0f, 1.0f, 1.8f)),
                        bandOffsets10 = listOf(-0.0f, -0.2f, -0.8f, -1.2f, -0.3f, -0.0f, 0.1f, 1.0f, 0.1f, 0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "moondrop_space_travel",
                name = "Space Travel",
                brand = "Moondrop",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.9f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 150.0f, -2.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, -1.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 3.0f, 2.5f)),
                        bandOffsets10 = listOf(-0.1f, -0.4f, -1.8f, -0.9f, -0.2f, -0.1f, -0.3f, -0.0f, 0.9f, 0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 2.5f, 2.5f)),
                        bandOffsets10 = listOf(-2.5f, -2.2f, -0.7f, -0.1f, -0.0f, 0.0f, 0.1f, 0.5f, 0.8f, 0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "tangzu_waner_sg",
                name = "Wan'er S.G",
                brand = "Tangzu",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -1.5f, 1.2f), BiquadBand(BiquadFilterType.PEAKING, 3200.0f, -1.5f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 6500.0f, -2.0f, 2.5f)),
                        bandOffsets10 = listOf(-0.0f, -0.1f, -0.6f, -1.2f, -0.2f, -0.1f, -0.4f, -1.1f, -1.0f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 200.0f, -1.5f, 1.2f)),
                        bandOffsets10 = listOf(-1.5f, -1.4f, -1.1f, -1.2f, -0.2f, -0.0f, -0.0f, -0.0f, -0.0f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "tangzu_fudu",
                name = "Fudu",
                brand = "Tangzu",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -1.4f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 180.0f, -2.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 4000.0f, 1.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -2.5f, 2.0f)),
                        bandOffsets10 = listOf(-0.1f, -0.3f, -1.3f, -1.4f, -0.3f, -0.0f, 0.2f, 1.2f, -2.3f, -0.2f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 180.0f, -1.5f, 1.0f)),
                        bandOffsets10 = listOf(-2.5f, -2.4f, -1.7f, -1.1f, -0.2f, -0.1f, -0.0f, -0.0f, -0.0f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "tangzu_zetian_wu",
                name = "Zetian Wu",
                brand = "Tangzu",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 2500.0f, 1.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7500.0f, -3.0f, 3.0f)),
                        bandOffsets10 = listOf(1.5f, 1.1f, 0.2f, 0.0f, 0.0f, 0.1f, 1.0f, 0.3f, -2.5f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 2500.0f, 1.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7500.0f, -3.0f, 3.0f)),
                        bandOffsets10 = listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.1f, 0.7f, 0.1f, -2.6f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "truthear_zero",
                name = "Zero",
                brand = "Truthear",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 2500.0f, -1.5f, 1.8f), BiquadBand(BiquadFilterType.PEAKING, 5500.0f, 2.0f, 2.5f), BiquadBand(BiquadFilterType.PEAKING, 9000.0f, -2.0f, 2.0f)),
                        bandOffsets10 = listOf(-0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.1f, -0.9f, 0.0f, -1.3f, -0.3f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 120.0f, -3.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 2500.0f, -1.5f, 1.8f)),
                        bandOffsets10 = listOf(-3.0f, -2.8f, -1.4f, -0.2f, -0.0f, -0.1f, -0.9f, -0.4f, -0.1f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "truthear_zero_red",
                name = "Zero:RED",
                brand = "Truthear",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 105.0f, 2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 1.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 1.5f, 2.0f)),
                        bandOffsets10 = listOf(2.5f, 2.2f, 0.8f, 0.1f, 0.0f, 0.1f, 0.4f, 1.0f, 0.7f, 0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.2f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 0.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 1.0f, 2.0f)),
                        bandOffsets10 = listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.2f, 0.5f, 0.5f, 0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "truthear_hexa",
                name = "Hexa",
                brand = "Truthear",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -3.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 105.0f, 3.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 1.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 6500.0f, -1.5f, 2.5f)),
                        bandOffsets10 = listOf(3.5f, 3.1f, 1.2f, 0.1f, 0.0f, 0.1f, 0.4f, 0.4f, -0.6f, -0.0f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 1.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 0.5f, 1.5f)),
                        bandOffsets10 = listOf(1.0f, 0.7f, 0.1f, 0.0f, 0.0f, 0.0f, 0.2f, 0.3f, 0.0f, 0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "truthear_nova",
                name = "Nova",
                brand = "Truthear",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -1.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 250.0f, 1.0f, 1.2f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, -1.5f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 1.0f, 2.0f)),
                        bandOffsets10 = listOf(0.0f, 0.0f, 0.2f, 1.0f, 0.2f, 0.0f, -0.4f, -0.4f, 0.4f, 0.0f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, -1.5f, 2.0f)),
                        bandOffsets10 = listOf(-2.5f, -2.2f, -0.7f, -0.1f, -0.0f, -0.1f, -0.4f, -0.6f, -0.1f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "truthear_hola",
                name = "Hola",
                brand = "Truthear",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.2f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -2.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3500.0f, 2.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, 1.5f, 2.0f)),
                        bandOffsets10 = listOf(-0.1f, -0.2f, -1.0f, -1.7f, -0.4f, 0.0f, 0.5f, 1.9f, 1.4f, 0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3500.0f, 1.5f, 1.5f)),
                        bandOffsets10 = listOf(-2.0f, -1.7f, -0.6f, -0.0f, 0.0f, 0.1f, 0.4f, 1.3f, 0.2f, 0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "truthear_gate",
                name = "Gate",
                brand = "Truthear",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 90.0f, 1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 5000.0f, -2.0f, 2.5f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -2.5f, 2.0f)),
                        bandOffsets10 = listOf(1.5f, 1.2f, 0.3f, 0.0f, -0.0f, -0.0f, -0.1f, -1.1f, -2.8f, -0.3f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 5000.0f, -2.0f, 2.5f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -2.5f, 2.0f)),
                        bandOffsets10 = listOf(-0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.1f, -1.1f, -2.8f, -0.3f)
                    ),
                )
            ),
            AcousticModel(
                id = "7hz_zero",
                name = "Zero",
                brand = "7Hz",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -3.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 105.0f, 3.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 1.5f, 2.5f), BiquadBand(BiquadFilterType.PEAKING, 12000.0f, -2.0f, 2.0f)),
                        bandOffsets10 = listOf(3.0f, 2.7f, 1.0f, 0.1f, 0.0f, 0.0f, 0.0f, 0.2f, -0.1f, -0.8f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 1.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 1.0f, 2.5f)),
                        bandOffsets10 = listOf(1.0f, 0.7f, 0.1f, 0.0f, 0.0f, 0.0f, 0.0f, 0.2f, 0.3f, 0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "7hz_zero_2",
                name = "Zero 2",
                brand = "7Hz",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -1.0f, 1.2f), BiquadBand(BiquadFilterType.PEAKING, 3200.0f, -1.0f, 1.8f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -1.5f, 2.0f)),
                        bandOffsets10 = listOf(-0.0f, -0.1f, -0.4f, -0.8f, -0.1f, -0.1f, -0.3f, -0.8f, -1.6f, -0.2f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3200.0f, -1.0f, 1.8f)),
                        bandOffsets10 = listOf(-2.0f, -1.7f, -0.6f, -0.0f, -0.0f, -0.0f, -0.2f, -0.6f, -0.1f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "7hz_timeless",
                name = "Timeless",
                brand = "7Hz",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -1.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 2200.0f, 2.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -4.0f, 3.0f)),
                        bandOffsets10 = listOf(-0.0f, -0.2f, -0.8f, -1.2f, -0.2f, 0.2f, 1.8f, 0.2f, -3.9f, -0.2f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -3.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -4.0f, 3.0f)),
                        bandOffsets10 = listOf(-3.0f, -2.6f, -0.9f, -0.1f, -0.0f, -0.0f, -0.0f, -0.2f, -4.0f, -0.2f)
                    ),
                )
            ),
            AcousticModel(
                id = "7hz_legato",
                name = "Legato",
                brand = "7Hz",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -3.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 150.0f, -6.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 300.0f, -3.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 3.0f, 1.5f)),
                        bandOffsets10 = listOf(-6.0f, -6.0f, -4.7f, -3.8f, -1.6f, -0.2f, 1.1f, 1.7f, 0.2f, 0.0f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -3.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 150.0f, -8.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 300.0f, -4.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 3.0f, 1.5f)),
                        bandOffsets10 = listOf(-8.5f, -8.4f, -6.5f, -4.6f, -1.9f, -0.2f, 1.1f, 1.7f, 0.2f, 0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "kiwi_ears_cadenza",
                name = "Cadenza",
                brand = "Kiwi Ears",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -1.9f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -1.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, -1.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 2.0f, 2.0f)),
                        bandOffsets10 = listOf(-0.0f, -0.1f, -0.5f, -0.8f, -0.2f, -0.1f, -0.3f, -0.0f, 0.8f, 0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 1.5f, 2.0f)),
                        bandOffsets10 = listOf(-1.5f, -1.3f, -0.4f, -0.0f, 0.0f, 0.0f, 0.1f, 0.4f, 0.6f, 0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "kiwi_ears_quintet",
                name = "Quintet",
                brand = "Kiwi Ears",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 90.0f, 1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, -3.0f, 2.5f), BiquadBand(BiquadFilterType.PEAKING, 10000.0f, -3.5f, 3.0f)),
                        bandOffsets10 = listOf(1.5f, 1.2f, 0.3f, 0.0f, -0.0f, -0.0f, -0.1f, -0.7f, -2.2f, -0.5f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 6000.0f, -3.0f, 2.5f), BiquadBand(BiquadFilterType.PEAKING, 10000.0f, -3.5f, 3.0f)),
                        bandOffsets10 = listOf(-0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.1f, -0.7f, -2.2f, -0.5f)
                    ),
                )
            ),
            AcousticModel(
                id = "kiwi_ears_orchestra_lite",
                name = "Orchestra Lite",
                brand = "Kiwi Ears",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -4.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 105.0f, 4.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 2500.0f, 1.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, -1.5f, 2.5f)),
                        bandOffsets10 = listOf(4.0f, 3.6f, 1.3f, 0.1f, 0.0f, 0.1f, 0.7f, 0.2f, -1.0f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 2500.0f, 0.5f, 1.5f)),
                        bandOffsets10 = listOf(1.5f, 1.1f, 0.2f, 0.0f, 0.0f, 0.0f, 0.3f, 0.2f, 0.0f, 0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "sennheiser_hd600",
                name = "HD600",
                brand = "Sennheiser",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -5.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 105.0f, 5.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 200.0f, -1.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3300.0f, -2.0f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 2.5f, 2.5f)),
                        bandOffsets10 = listOf(5.4f, 4.7f, 1.1f, -1.1f, -0.3f, -0.1f, -0.3f, -0.8f, 0.7f, 0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3300.0f, -1.5f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 2.0f, 2.5f)),
                        bandOffsets10 = listOf(2.4f, 1.8f, 0.4f, 0.0f, -0.0f, -0.0f, -0.2f, -0.6f, 0.6f, 0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "sennheiser_hd650",
                name = "HD650",
                brand = "Sennheiser",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -6.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 105.0f, 6.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 220.0f, -2.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3200.0f, -1.5f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 3.0f, 2.5f)),
                        bandOffsets10 = listOf(5.9f, 5.1f, 1.2f, -1.7f, -0.5f, -0.1f, -0.3f, -0.3f, 0.9f, 0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 220.0f, -1.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 2.5f, 2.5f)),
                        bandOffsets10 = listOf(2.4f, 1.7f, -0.3f, -1.4f, -0.3f, -0.1f, 0.0f, 0.5f, 0.8f, 0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "sennheiser_hd560s",
                name = "HD560S",
                brand = "Sennheiser",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -3.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 90.0f, 3.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 1500.0f, 1.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 5000.0f, -3.5f, 3.0f)),
                        bandOffsets10 = listOf(3.5f, 2.8f, 0.7f, 0.1f, 0.1f, 0.6f, 0.8f, -1.1f, -0.3f, -0.0f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 1.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 5000.0f, -3.5f, 3.0f)),
                        bandOffsets10 = listOf(1.0f, 0.7f, 0.1f, 0.0f, -0.0f, -0.0f, -0.1f, -1.2f, -0.4f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "sennheiser_hd800s",
                name = "HD800S",
                brand = "Sennheiser",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -6.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 105.0f, 6.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 1500.0f, 2.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 5800.0f, -5.0f, 3.5f), BiquadBand(BiquadFilterType.PEAKING, 9500.0f, -2.5f, 3.0f)),
                        bandOffsets10 = listOf(6.4f, 5.7f, 2.2f, 0.2f, 0.1f, 0.8f, 1.1f, -0.6f, -2.0f, -0.3f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 5800.0f, -5.0f, 3.5f)),
                        bandOffsets10 = listOf(2.4f, 1.8f, 0.4f, 0.0f, -0.0f, -0.0f, -0.1f, -0.6f, -0.8f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "sennheiser_ie200",
                name = "IE200",
                brand = "Sennheiser",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 90.0f, 2.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, -1.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, -3.5f, 3.0f)),
                        bandOffsets10 = listOf(2.0f, 1.6f, 0.4f, 0.0f, -0.0f, -0.1f, -0.6f, -1.1f, -2.2f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 3000.0f, -1.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, -3.5f, 3.0f)),
                        bandOffsets10 = listOf(-0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.1f, -0.6f, -1.1f, -2.2f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "sennheiser_ie600",
                name = "IE600",
                brand = "Sennheiser",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, 1.5f, 1.2f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, -1.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 6500.0f, -2.5f, 2.5f)),
                        bandOffsets10 = listOf(0.0f, 0.1f, 0.6f, 1.2f, 0.2f, -0.0f, -0.4f, -0.9f, -1.3f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 6500.0f, -2.5f, 2.5f)),
                        bandOffsets10 = listOf(-2.5f, -2.2f, -0.7f, -0.1f, -0.0f, -0.0f, -0.0f, -0.3f, -1.2f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "sennheiser_momentum_4",
                name = "Momentum 4",
                brand = "Sennheiser",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -2.5f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 120.0f, -3.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 200.0f, -3.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 2.5f, 1.8f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, -2.0f, 2.5f)),
                        bandOffsets10 = listOf(-3.6f, -3.6f, -3.1f, -2.7f, -0.5f, -0.0f, 0.7f, 1.0f, -1.2f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 120.0f, -6.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 200.0f, -3.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 2.5f, 1.8f)),
                        bandOffsets10 = listOf(-6.1f, -6.0f, -4.6f, -3.2f, -0.7f, -0.0f, 0.7f, 1.2f, 0.1f, 0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "sony_wh1000xm4",
                name = "WH-1000XM4",
                brand = "Sony",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -4.8f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 180.0f, -5.5f, 0.8f), BiquadBand(BiquadFilterType.PEAKING, 750.0f, 2.0f, 1.2f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 4.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, 3.5f, 2.0f)),
                        bandOffsets10 = listOf(-0.3f, -1.1f, -4.0f, -4.0f, -0.1f, 1.3f, 2.0f, 2.9f, 3.9f, 0.4f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -4.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -3.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 180.0f, -6.0f, 0.8f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 4.5f, 1.5f)),
                        bandOffsets10 = listOf(-3.3f, -3.8f, -5.3f, -4.7f, -1.3f, -0.1f, 1.7f, 2.5f, 0.4f, 0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "sony_wh1000xm5",
                name = "WH-1000XM5",
                brand = "Sony",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -4.4f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 150.0f, -4.0f, 0.9f), BiquadBand(BiquadFilterType.PEAKING, 1000.0f, 1.5f, 1.2f), BiquadBand(BiquadFilterType.PEAKING, 3200.0f, 4.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7500.0f, 2.5f, 2.0f)),
                        bandOffsets10 = listOf(-0.2f, -1.0f, -3.6f, -2.0f, -0.1f, 1.6f, 1.7f, 3.1f, 2.7f, 0.3f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -4.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 150.0f, -4.5f, 0.9f), BiquadBand(BiquadFilterType.PEAKING, 3200.0f, 4.0f, 1.5f)),
                        bandOffsets10 = listOf(-2.7f, -3.3f, -4.8f, -2.4f, -0.5f, 0.1f, 1.3f, 2.7f, 0.4f, 0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "sony_wf1000xm4",
                name = "WF-1000XM4",
                brand = "Sony",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -3.4f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 180.0f, -3.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 2500.0f, 2.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 3.0f, 2.0f)),
                        bandOffsets10 = listOf(-0.1f, -0.4f, -1.9f, -2.1f, -0.4f, 0.2f, 1.8f, 1.6f, 1.4f, 0.2f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 180.0f, -3.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 2500.0f, 2.5f, 1.5f)),
                        bandOffsets10 = listOf(-2.1f, -2.1f, -2.5f, -2.1f, -0.4f, 0.1f, 1.7f, 0.8f, 0.1f, 0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "sony_wf1000xm5",
                name = "WF-1000XM5",
                brand = "Sony",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 160.0f, -2.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 2.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7500.0f, -1.5f, 2.5f)),
                        bandOffsets10 = listOf(-0.1f, -0.4f, -2.0f, -1.3f, -0.3f, 0.0f, 0.7f, 1.0f, -1.2f, -0.0f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 2.0f, 1.5f)),
                        bandOffsets10 = listOf(-2.5f, -2.2f, -0.7f, -0.1f, 0.0f, 0.1f, 0.8f, 1.1f, 0.2f, 0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "sony_ier_m9",
                name = "IER-M9",
                brand = "Sony",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.8f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 90.0f, 2.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 2.0f, 1.8f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 2.5f, 2.5f)),
                        bandOffsets10 = listOf(2.0f, 1.6f, 0.4f, 0.0f, 0.0f, 0.1f, 0.7f, 1.4f, 0.9f, 0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.3f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 1.5f, 1.8f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 2.0f, 2.5f)),
                        bandOffsets10 = listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.1f, 0.5f, 1.1f, 0.7f, 0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "apple_airpods_pro",
                name = "AirPods Pro",
                brand = "Apple",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -3.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 3.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, -2.0f, 2.5f), BiquadBand(BiquadFilterType.PEAKING, 10000.0f, 2.0f, 2.0f)),
                        bandOffsets10 = listOf(2.9f, 2.2f, 0.4f, 0.0f, 0.0f, -0.0f, -0.0f, -0.3f, 0.5f, 0.4f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 1.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, -2.0f, 2.5f)),
                        bandOffsets10 = listOf(1.0f, 0.7f, 0.1f, 0.0f, -0.0f, -0.0f, -0.0f, -0.4f, -0.6f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "apple_airpods_pro_2",
                name = "AirPods Pro 2",
                brand = "Apple",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 50.0f, 2.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 2500.0f, -1.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -2.0f, 2.5f)),
                        bandOffsets10 = listOf(1.7f, 0.6f, 0.0f, -0.0f, -0.0f, -0.1f, -0.7f, -0.5f, -2.1f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, -1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -2.0f, 2.5f)),
                        bandOffsets10 = listOf(-1.5f, -1.1f, -0.2f, -0.0f, -0.0f, -0.0f, -0.0f, -0.1f, -2.0f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "apple_airpods_max",
                name = "AirPods Max",
                brand = "Apple",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -2.9f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -1.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 4000.0f, 3.0f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -2.5f, 2.5f)),
                        bandOffsets10 = listOf(-0.0f, -0.2f, -0.8f, -1.2f, -0.3f, -0.0f, 0.3f, 2.8f, -2.2f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -3.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 4000.0f, 3.0f, 2.0f)),
                        bandOffsets10 = listOf(-2.0f, -1.7f, -0.6f, -0.0f, 0.0f, 0.1f, 0.3f, 3.0f, 0.3f, 0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "apple_airpods_3",
                name = "AirPods 3",
                brand = "Apple",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -5.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 120.0f, 5.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3500.0f, -2.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, -2.5f, 2.5f)),
                        bandOffsets10 = listOf(5.0f, 4.7f, 2.3f, 0.3f, -0.0f, -0.1f, -0.5f, -2.2f, -1.0f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, 2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3500.0f, -2.0f, 1.5f)),
                        bandOffsets10 = listOf(2.5f, 2.2f, 0.7f, 0.1f, -0.0f, -0.1f, -0.5f, -1.7f, -0.2f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "apple_airpods_4",
                name = "AirPods 4",
                brand = "Apple",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -4.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 110.0f, 4.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, -1.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, -2.0f, 2.5f)),
                        bandOffsets10 = listOf(4.0f, 3.6f, 1.5f, 0.1f, -0.0f, -0.1f, -0.6f, -1.1f, -1.5f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 90.0f, 1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, -1.5f, 1.5f)),
                        bandOffsets10 = listOf(1.5f, 1.2f, 0.3f, 0.0f, -0.0f, -0.1f, -0.6f, -0.8f, -0.1f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "samsung_galaxy_buds_2_pro",
                name = "Galaxy Buds 2 Pro",
                brand = "Samsung",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -1.4f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -1.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, -1.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 1.5f, 2.5f)),
                        bandOffsets10 = listOf(-0.0f, -0.1f, -0.5f, -0.8f, -0.2f, -0.1f, -0.4f, -0.3f, 0.4f, 0.0f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, -1.0f, 1.5f)),
                        bandOffsets10 = listOf(-2.5f, -2.2f, -0.7f, -0.1f, -0.0f, -0.1f, -0.4f, -0.6f, -0.1f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "samsung_galaxy_buds_fe",
                name = "Galaxy Buds FE",
                brand = "Samsung",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 150.0f, -2.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3500.0f, -1.5f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, 2.0f, 2.0f)),
                        bandOffsets10 = listOf(-0.1f, -0.4f, -1.8f, -0.9f, -0.2f, -0.1f, -0.2f, -1.0f, 1.9f, 0.2f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -3.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 150.0f, -1.5f, 1.0f)),
                        bandOffsets10 = listOf(-3.0f, -2.9f, -2.2f, -0.8f, -0.2f, -0.0f, -0.0f, -0.0f, -0.0f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "samsung_galaxy_buds_pro",
                name = "Galaxy Buds Pro",
                brand = "Samsung",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 2500.0f, -2.0f, 1.8f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, 2.0f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 10000.0f, -2.5f, 2.5f)),
                        bandOffsets10 = listOf(-0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.1f, -1.2f, -0.0f, -0.3f, -0.3f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 2500.0f, -2.0f, 1.8f)),
                        bandOffsets10 = listOf(-2.5f, -2.2f, -0.7f, -0.1f, -0.0f, -0.1f, -1.2f, -0.5f, -0.1f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "nothing_ear",
                name = "Nothing Ear",
                brand = "Nothing",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 150.0f, -2.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 1500.0f, 2.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 6500.0f, -3.5f, 2.5f)),
                        bandOffsets10 = listOf(-0.1f, -0.5f, -2.2f, -1.1f, -0.1f, 0.7f, 1.1f, -0.3f, -1.6f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -3.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 1500.0f, 2.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 6500.0f, -3.5f, 2.5f)),
                        bandOffsets10 = listOf(-3.5f, -3.0f, -1.0f, -0.1f, 0.1f, 0.8f, 1.1f, -0.3f, -1.6f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "nothing_ear_2",
                name = "Ear (2)",
                brand = "Nothing",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 2.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 200.0f, 1.5f, 1.2f), BiquadBand(BiquadFilterType.PEAKING, 5500.0f, -4.0f, 3.0f)),
                        bandOffsets10 = listOf(2.0f, 1.6f, 0.9f, 1.2f, 0.2f, 0.0f, -0.1f, -0.8f, -0.6f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, 1.5f, 1.2f), BiquadBand(BiquadFilterType.PEAKING, 5500.0f, -4.0f, 3.0f)),
                        bandOffsets10 = listOf(0.0f, 0.1f, 0.6f, 1.2f, 0.2f, 0.0f, -0.1f, -0.8f, -0.6f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "nothing_ear_a",
                name = "Ear (a)",
                brand = "Nothing",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -1.5f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 120.0f, -3.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 1.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, -2.5f, 2.5f)),
                        bandOffsets10 = listOf(-3.0f, -2.8f, -1.4f, -0.1f, 0.0f, 0.1f, 0.5f, 0.6f, -1.6f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 120.0f, -5.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 1.5f, 1.5f)),
                        bandOffsets10 = listOf(-5.5f, -5.1f, -2.5f, -0.3f, 0.0f, 0.1f, 0.6f, 0.8f, 0.1f, 0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "cmf_buds",
                name = "CMF Buds",
                brand = "CMF",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 150.0f, -5.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 250.0f, -2.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 2.5f, 1.5f)),
                        bandOffsets10 = listOf(-5.0f, -5.0f, -4.1f, -3.1f, -0.8f, -0.0f, 0.9f, 1.4f, 0.2f, 0.0f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 150.0f, -7.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 250.0f, -3.0f, 1.0f)),
                        bandOffsets10 = listOf(-7.5f, -7.5f, -5.9f, -3.9f, -1.0f, -0.2f, -0.0f, -0.0f, -0.0f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "cmf_buds_pro_2",
                name = "CMF Buds Pro 2",
                brand = "CMF",
                type = AcousticDeviceType.TWS,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 120.0f, -3.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 200.0f, -2.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, -2.5f, 2.5f)),
                        bandOffsets10 = listOf(-3.5f, -3.5f, -2.6f, -1.8f, -0.4f, -0.1f, -0.1f, -0.5f, -0.8f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 120.0f, -6.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 200.0f, -2.0f, 1.0f)),
                        bandOffsets10 = listOf(-6.0f, -5.8f, -3.8f, -2.0f, -0.4f, -0.1f, -0.0f, -0.0f, -0.0f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "audiotechnica_ath_m50x",
                name = "ATH-M50x",
                brand = "Audio-Technica",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -2.5f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 180.0f, -4.0f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 500.0f, 2.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 3500.0f, 2.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 9000.0f, -5.0f, 3.5f)),
                        bandOffsets10 = listOf(-0.1f, -0.5f, -2.5f, -2.4f, 1.4f, 0.3f, 0.6f, 2.0f, -2.7f, -0.2f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 180.0f, -4.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 9000.0f, -5.0f, 3.5f)),
                        bandOffsets10 = listOf(-2.6f, -2.8f, -3.6f, -3.2f, -0.7f, -0.2f, -0.1f, -0.1f, -3.0f, -0.3f)
                    ),
                )
            ),
            AcousticModel(
                id = "audiotechnica_ath_r70x",
                name = "ATH-R70x",
                brand = "Audio-Technica",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -4.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 90.0f, 4.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 1500.0f, 1.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, -1.5f, 2.5f)),
                        bandOffsets10 = listOf(4.4f, 3.6f, 1.0f, 0.1f, 0.1f, 0.4f, 0.5f, -0.1f, -1.0f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 70.0f, 1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, -1.5f, 2.5f)),
                        bandOffsets10 = listOf(1.4f, 0.9f, 0.1f, 0.0f, -0.0f, -0.0f, -0.0f, -0.2f, -1.0f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "beyerdynamic_dt_770_pro",
                name = "DT 770 Pro",
                brand = "Beyerdynamic",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -3.3f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 180.0f, -2.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 3.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 5800.0f, -5.5f, 3.5f), BiquadBand(BiquadFilterType.PEAKING, 8500.0f, -4.5f, 3.0f)),
                        bandOffsets10 = listOf(-0.1f, -0.3f, -1.6f, -1.7f, -0.3f, 0.1f, 1.2f, 1.1f, -4.6f, -0.3f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -3.4f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 3.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 5800.0f, -5.5f, 3.5f)),
                        bandOffsets10 = listOf(-2.5f, -2.2f, -0.7f, -0.0f, 0.0f, 0.2f, 1.3f, 1.3f, -0.6f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "beyerdynamic_dt_990_pro",
                name = "DT 990 Pro",
                brand = "Beyerdynamic",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -4.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 90.0f, 4.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 1500.0f, 2.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 5800.0f, -6.5f, 3.5f), BiquadBand(BiquadFilterType.PEAKING, 8500.0f, -6.0f, 3.5f)),
                        bandOffsets10 = listOf(3.9f, 3.2f, 0.9f, 0.1f, 0.1f, 1.0f, 1.3f, -0.8f, -6.1f, -0.4f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 70.0f, 1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 1500.0f, 2.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 5800.0f, -6.5f, 3.5f)),
                        bandOffsets10 = listOf(1.4f, 0.9f, 0.1f, 0.0f, 0.1f, 0.8f, 1.0f, -0.7f, -1.1f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "beyerdynamic_dt_1990_pro",
                name = "DT 1990 Pro",
                brand = "Beyerdynamic",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -3.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 85.0f, 3.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 1500.0f, 1.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 8200.0f, -7.0f, 4.0f)),
                        bandOffsets10 = listOf(3.4f, 2.7f, 0.6f, 0.1f, 0.1f, 0.6f, 0.8f, -0.1f, -6.7f, -0.2f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 70.0f, 1.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 8200.0f, -7.0f, 4.0f)),
                        bandOffsets10 = listOf(1.0f, 0.6f, 0.1f, 0.0f, -0.0f, -0.0f, -0.0f, -0.2f, -6.7f, -0.2f)
                    ),
                )
            ),
            AcousticModel(
                id = "hifiman_sundara",
                name = "Sundara",
                brand = "Hifiman",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -4.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 4.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 1800.0f, 2.0f, 1.8f), BiquadBand(BiquadFilterType.PEAKING, 9000.0f, -3.0f, 3.0f)),
                        bandOffsets10 = listOf(4.4f, 3.3f, 0.7f, 0.1f, 0.1f, 0.3f, 1.7f, 0.1f, -2.0f, -0.2f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 60.0f, 1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 1800.0f, 1.5f, 1.8f), BiquadBand(BiquadFilterType.PEAKING, 9000.0f, -3.0f, 3.0f)),
                        bandOffsets10 = listOf(1.4f, 0.7f, 0.1f, 0.0f, 0.0f, 0.2f, 1.3f, 0.0f, -2.0f, -0.2f)
                    ),
                )
            ),
            AcousticModel(
                id = "hifiman_edition_xs",
                name = "Edition XS",
                brand = "Hifiman",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -3.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 75.0f, 3.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 1800.0f, 2.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7500.0f, -2.5f, 2.5f)),
                        bandOffsets10 = listOf(3.4f, 2.4f, 0.4f, 0.0f, 0.1f, 0.6f, 2.2f, 0.1f, -2.2f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 60.0f, 1.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 1800.0f, 2.0f, 1.5f)),
                        bandOffsets10 = listOf(0.9f, 0.5f, 0.1f, 0.0f, 0.1f, 0.4f, 1.8f, 0.2f, 0.0f, 0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "hifiman_arya",
                name = "Arya",
                brand = "Hifiman",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -3.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 75.0f, 3.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 2000.0f, 2.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 5000.0f, -3.0f, 3.0f)),
                        bandOffsets10 = listOf(3.4f, 2.4f, 0.4f, 0.0f, 0.1f, 0.4f, 2.4f, -0.7f, -0.2f, -0.0f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -2.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 60.0f, 1.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 2000.0f, 2.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 5000.0f, -3.0f, 3.0f)),
                        bandOffsets10 = listOf(0.9f, 0.5f, 0.1f, 0.0f, 0.1f, 0.3f, 1.9f, -0.7f, -0.3f, -0.0f)
                    ),
                )
            ),
            AcousticModel(
                id = "kz_zsn_pro_x",
                name = "ZSN Pro X",
                brand = "KZ",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -2.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 2500.0f, -3.5f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -7.0f, 3.5f)),
                        bandOffsets10 = listOf(-0.1f, -0.3f, -1.3f, -2.1f, -0.5f, -0.3f, -2.0f, -1.0f, -7.1f, -0.3f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -3.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 2500.0f, -3.5f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -7.0f, 3.5f)),
                        bandOffsets10 = listOf(-3.0f, -2.6f, -0.9f, -0.1f, -0.0f, -0.2f, -2.0f, -1.0f, -7.1f, -0.3f)
                    ),
                )
            ),
            AcousticModel(
                id = "kz_castor",
                name = "Castor",
                brand = "KZ",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -1.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, -1.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, -3.0f, 2.5f)),
                        bandOffsets10 = listOf(-0.0f, -0.2f, -0.8f, -1.3f, -0.3f, -0.1f, -0.5f, -0.9f, -2.1f, -0.2f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, -3.0f, 2.5f)),
                        bandOffsets10 = listOf(-2.5f, -2.2f, -0.7f, -0.1f, -0.0f, -0.0f, -0.0f, -0.3f, -2.1f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "kz_pr2",
                name = "PR2",
                brand = "KZ",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -2.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 80.0f, 2.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 3000.0f, 1.0f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 10000.0f, -5.5f, 3.0f)),
                        bandOffsets10 = listOf(2.0f, 1.5f, 0.3f, 0.0f, 0.0f, 0.1f, 0.4f, 0.4f, -1.9f, -0.6f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 10000.0f, -5.5f, 3.0f)),
                        bandOffsets10 = listOf(-0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.1f, -2.0f, -0.6f)
                    ),
                )
            ),
            AcousticModel(
                id = "shure_se215",
                name = "SE215",
                brand = "Shure",
                type = AcousticDeviceType.IEM,
                defaultTarget = CalibrationTarget.HARMAN_IN_EAR_2019,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -6.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 200.0f, -3.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 2500.0f, 2.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, 5.0f, 2.0f), BiquadBand(BiquadFilterType.HIGH_SHELF, 10000.0f, 4.0f, 0.71f)),
                        bandOffsets10 = listOf(-0.1f, -0.4f, -1.8f, -2.9f, -0.6f, 0.1f, 1.8f, 1.7f, 5.2f, 3.9f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -5.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 100.0f, -2.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 200.0f, -3.5f, 1.0f), BiquadBand(BiquadFilterType.PEAKING, 7000.0f, 5.0f, 2.0f)),
                        bandOffsets10 = listOf(-2.1f, -2.1f, -2.4f, -2.9f, -0.7f, -0.1f, 0.1f, 0.8f, 3.9f, 0.4f)
                    ),
                )
            ),
            AcousticModel(
                id = "shure_aonic_50",
                name = "AONIC 50",
                brand = "Shure",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -4.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 90.0f, 4.0f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 1500.0f, 1.5f, 1.5f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, -2.0f, 2.5f)),
                        bandOffsets10 = listOf(3.9f, 3.2f, 0.9f, 0.1f, 0.1f, 0.6f, 0.8f, -0.3f, -0.6f, -0.1f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = -1.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 70.0f, 1.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 6000.0f, -2.0f, 2.5f)),
                        bandOffsets10 = listOf(1.4f, 0.9f, 0.1f, 0.0f, -0.0f, -0.0f, -0.0f, -0.4f, -0.6f, -0.1f)
                    ),
                )
            ),
            AcousticModel(
                id = "shure_srh840",
                name = "SRH840",
                brand = "Shure",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -2.6f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 90.0f, 2.5f, 0.71f), BiquadBand(BiquadFilterType.PEAKING, 4000.0f, -2.0f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -2.0f, 2.5f)),
                        bandOffsets10 = listOf(2.5f, 2.0f, 0.5f, 0.0f, -0.0f, -0.0f, -0.2f, -2.1f, -2.2f, -0.2f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.PEAKING, 4000.0f, -2.0f, 2.0f), BiquadBand(BiquadFilterType.PEAKING, 8000.0f, -2.0f, 2.5f)),
                        bandOffsets10 = listOf(-0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.0f, -0.2f, -2.1f, -2.2f, -0.2f)
                    ),
                )
            ),
            AcousticModel(
                id = "universal_studio_neutral",
                name = "Universal Studio Neutral",
                brand = "BitChord Reference",
                type = AcousticDeviceType.OVER_EAR,
                defaultTarget = CalibrationTarget.HARMAN_OVER_EAR_2018,
                targetProfiles = mapOf(
                    CalibrationTarget.HARMAN_OVER_EAR_2018 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_OVER_EAR_2018,
                        preampDb = -4.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 105.0f, 4.0f, 0.71f), BiquadBand(BiquadFilterType.HIGH_SHELF, 10000.0f, -1.5f, 0.71f)),
                        bandOffsets10 = listOf(4.0f, 3.6f, 1.3f, 0.1f, 0.0f, 0.0f, -0.0f, -0.0f, -0.4f, -1.3f)
                    ),
                    CalibrationTarget.HARMAN_IN_EAR_2019 to AutoEqProfile(
                        target = CalibrationTarget.HARMAN_IN_EAR_2019,
                        preampDb = -6.1f,
                        biquadBands = listOf(BiquadBand(BiquadFilterType.LOW_SHELF, 105.0f, 6.0f, 0.71f), BiquadBand(BiquadFilterType.HIGH_SHELF, 10000.0f, -1.0f, 0.71f)),
                        bandOffsets10 = listOf(6.0f, 5.3f, 2.0f, 0.2f, 0.0f, 0.0f, -0.0f, -0.0f, -0.3f, -0.9f)
                    ),
                    CalibrationTarget.IEF_NEUTRAL to AutoEqProfile(
                        target = CalibrationTarget.IEF_NEUTRAL,
                        preampDb = 0.0f,
                        biquadBands = listOf(),
                        bandOffsets10 = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                    ),
                )
            ),
        )
    }
}

/**
 * Bridges an [AcousticModel] with UI-level [com.music.bitchord.ui.screens.calibration.HeadphoneModel].
 */
fun AcousticModel.toHeadphoneModel(): com.music.bitchord.ui.screens.calibration.HeadphoneModel {
    val factor = when (type) {
        AcousticDeviceType.IEM -> com.music.bitchord.ui.screens.calibration.HeadphoneFormFactor.IEM
        AcousticDeviceType.OVER_EAR -> com.music.bitchord.ui.screens.calibration.HeadphoneFormFactor.OVER_EAR
        AcousticDeviceType.TWS -> com.music.bitchord.ui.screens.calibration.HeadphoneFormFactor.EARBUDS
    }
    return com.music.bitchord.ui.screens.calibration.HeadphoneModel(
        id = id,
        name = name,
        brand = brand,
        targetBaseline = defaultTarget.displayName,
        formFactor = factor,
        impedanceOhms = 32,
        sensitivityDb = 105f,
        description = "$brand $name calibrated baseline"
    )
}

