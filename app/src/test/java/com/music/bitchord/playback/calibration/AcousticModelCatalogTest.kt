package com.music.bitchord.playback.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Production validation tests for [AcousticModelCatalog] and [AutoEqProfile].
 *
 * Verifies model lookup, search ranking, fuzzy matching, filter coefficient validity,
 * bit-perfect pre-amp headroom, and EqCurve integration.
 */
class AcousticModelCatalogTest {

    @Before
    fun setUp() {
        AcousticModelCatalog.resetToDefault()
    }

    // ---- Model Lookup Tests ------------------------------------------------

    @Test
    fun `catalog contains all required audiophile and consumer brands`() {
        val brands = AcousticModelCatalog.getBrands()
        val requiredBrands = listOf(
            "Moondrop", "Tangzu", "Truthear", "7Hz", "Kiwi Ears",
            "Sennheiser", "Sony", "Apple", "Samsung", "Nothing",
            "CMF", "Audio-Technica", "Beyerdynamic", "Hifiman", "KZ", "Shure"
        )
        for (required in requiredBrands) {
            assertTrue("Expected brand '$required' in catalog brands $brands", brands.contains(required))
        }
    }

    @Test
    fun `lookup by id returns exact model`() {
        val hd600 = AcousticModelCatalog.getModelById("sennheiser_hd600")
        assertNotNull("HD600 must exist", hd600)
        assertEquals("HD600", hd600?.name)
        assertEquals("Sennheiser", hd600?.brand)
        assertEquals(AcousticDeviceType.OVER_EAR, hd600?.type)

        val b3 = AcousticModelCatalog.getModelById("moondrop_blessing_3")
        assertNotNull("Blessing 3 must exist", b3)
        assertEquals("Blessing 3", b3?.name)
        assertEquals("Moondrop", b3?.brand)
        assertEquals(AcousticDeviceType.IEM, b3?.type)

        val app2 = AcousticModelCatalog.getModelById("apple_airpods_pro_2")
        assertNotNull("AirPods Pro 2 must exist", app2)
        assertEquals("AirPods Pro 2", app2?.name)
        assertEquals("Apple", app2?.brand)
        assertEquals(AcousticDeviceType.TWS, app2?.type)
    }

    @Test
    fun `lookup by id is case insensitive and handles whitespace`() {
        val upper = AcousticModelCatalog.getModelById("SENNHEISER_HD600")
        assertNotNull("Upper-case ID should match", upper)
        assertEquals("HD600", upper?.name)

        val spaced = AcousticModelCatalog.getModelById("  moondrop_chu  ")
        assertNotNull("Spaced ID should match", spaced)
        assertEquals("Chu", spaced?.name)

        val nonExistent = AcousticModelCatalog.getModelById("non_existent_headphone_xyz")
        assertNull("Non-existent ID should return null", nonExistent)
    }

    @Test
    fun `lookup by brand returns all brand models`() {
        val moondropModels = AcousticModelCatalog.getModelsByBrand("Moondrop")
        assertTrue("Moondrop must have at least 9 models", moondropModels.size >= 9)

        val expectedMoondrop = listOf("Chu", "Chu II", "Aria", "Kato", "Blessing 2", "Blessing 3", "Variations", "Starfield", "Space Travel")
        for (name in expectedMoondrop) {
            assertTrue("Missing Moondrop model $name", moondropModels.any { it.name == name })
        }

        val sennheiserModels = AcousticModelCatalog.getModelsByBrand("Sennheiser")
        val expectedSennheiser = listOf("HD600", "HD650", "HD560S", "HD800S", "IE200", "IE600", "Momentum 4")
        for (name in expectedSennheiser) {
            assertTrue("Missing Sennheiser model $name", sennheiserModels.any { it.name == name })
        }

        val sonyModels = AcousticModelCatalog.getModelsByBrand("Sony")
        val expectedSony = listOf("WH-1000XM4", "WH-1000XM5", "WF-1000XM4", "WF-1000XM5", "IER-M9")
        for (name in expectedSony) {
            assertTrue("Missing Sony model $name", sonyModels.any { it.name == name })
        }

        val appleModels = AcousticModelCatalog.getModelsByBrand("Apple")
        val expectedApple = listOf("AirPods Pro", "AirPods Pro 2", "AirPods Max", "AirPods 3", "AirPods 4")
        for (name in expectedApple) {
            assertTrue("Missing Apple model $name", appleModels.any { it.name == name })
        }
    }

    @Test
    fun `lookup by device type correctly groups models`() {
        val iems = AcousticModelCatalog.getModelsByType(AcousticDeviceType.IEM)
        assertTrue(iems.isNotEmpty())
        assertTrue(iems.all { it.type == AcousticDeviceType.IEM })

        val overEars = AcousticModelCatalog.getModelsByType(AcousticDeviceType.OVER_EAR)
        assertTrue(overEars.isNotEmpty())
        assertTrue(overEars.all { it.type == AcousticDeviceType.OVER_EAR })

        val tws = AcousticModelCatalog.getModelsByType(AcousticDeviceType.TWS)
        assertTrue(tws.isNotEmpty())
        assertTrue(tws.all { it.type == AcousticDeviceType.TWS })
    }

    // ---- Search Ranking & Filtering Tests -----------------------------------

    @Test
    fun `exact model search ranks top result first`() {
        val results = AcousticModelCatalog.search("HD600")
        assertTrue("Search for 'HD600' must return results", results.isNotEmpty())
        assertEquals("sennheiser_hd600", results.first().id)
    }

    @Test
    fun `prefix search ranks matching models first`() {
        val results = AcousticModelCatalog.search("Chu")
        assertTrue(results.size >= 2)
        val firstTwoNames = results.take(2).map { it.name }
        assertTrue("First results must contain Chu", firstTwoNames.contains("Chu"))
        assertTrue("First results must contain Chu II", firstTwoNames.contains("Chu II"))
    }

    @Test
    fun `brand prefix search returns brand models`() {
        val results = AcousticModelCatalog.search("Senn")
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.brand == "Sennheiser" })
    }

    @Test
    fun `substring search finds model tokens`() {
        val results = AcousticModelCatalog.search("XM4")
        assertTrue(results.size >= 2)
        val ids = results.map { it.id }
        assertTrue(ids.contains("sony_wh1000xm4"))
        assertTrue(ids.contains("sony_wf1000xm4"))
    }

    @Test
    fun `fuzzy typo search recovers intended models`() {
        // "senheiser" (missing 'n')
        val sennheiserResults = AcousticModelCatalog.search("senheiser")
        assertTrue("Fuzzy query 'senheiser' must find Sennheiser models", sennheiserResults.isNotEmpty())
        assertTrue(sennheiserResults.any { it.brand == "Sennheiser" })

        // "airpod" (missing plural 's')
        val airpodResults = AcousticModelCatalog.search("airpod")
        assertTrue("Fuzzy query 'airpod' must find AirPods models", airpodResults.isNotEmpty())
        assertTrue(airpodResults.any { it.name.startsWith("AirPods") })
    }

    @Test
    fun `search with brand filter restricts results`() {
        // "Zero" exists in both Truthear and 7Hz
        val truthearZero = AcousticModelCatalog.search("Zero", brandFilter = "Truthear")
        assertTrue(truthearZero.isNotEmpty())
        assertTrue(truthearZero.all { it.brand == "Truthear" })
        assertTrue(truthearZero.none { it.brand == "7Hz" })

        val sevenHzZero = AcousticModelCatalog.search("Zero", brandFilter = "7Hz")
        assertTrue(sevenHzZero.isNotEmpty())
        assertTrue(sevenHzZero.all { it.brand == "7Hz" })
        assertTrue(sevenHzZero.none { it.brand == "Truthear" })
    }

    @Test
    fun `search with type filter restricts results`() {
        val overEarResults = AcousticModelCatalog.search("Pro", typeFilter = AcousticDeviceType.OVER_EAR)
        assertTrue(overEarResults.isNotEmpty())
        assertTrue(overEarResults.all { it.type == AcousticDeviceType.OVER_EAR })
        // AirPods Pro should not appear because it is TWS
        assertFalse(overEarResults.any { it.id == "apple_airpods_pro" })
    }

    @Test
    fun `empty query returns all models sorted alphabetically`() {
        val all = AcousticModelCatalog.search("")
        assertEquals(AcousticModelCatalog.getModels().size, all.size)
    }

    // ---- Coefficient Validity Tests ----------------------------------------

    @Test
    fun `all models have valid biquad bands and targets`() {
        val models = AcousticModelCatalog.getModels()
        assertTrue("Catalog must not be empty", models.isNotEmpty())

        for (model in models) {
            assertTrue("Model id must not be blank", model.id.isNotBlank())
            assertTrue("Model name must not be blank", model.name.isNotBlank())
            assertTrue("Model brand must not be blank", model.brand.isNotBlank())

            for ((target, profile) in model.targetProfiles) {
                assertEquals(target, profile.target)
                assertEquals("10-band offsets must have exactly 10 values", 10, profile.bandOffsets10.size)

                for (band in profile.biquadBands) {
                    assertTrue(
                        "Band frequency ${band.frequencyHz} Hz in model ${model.id} must be in [20..20000]",
                        band.frequencyHz in 20f..20000f
                    )
                    assertTrue(
                        "Band Q ${band.q} in model ${model.id} must be positive and reasonable",
                        band.q in 0.1f..20f
                    )
                    assertTrue(
                        "Band gain ${band.gainDb} dB in model ${model.id} must be in [-24..+24]",
                        band.gainDb in -24f..24f
                    )
                }
            }
        }
    }

    // ---- Bit-Perfect Headroom & Zero Pumping Tests ---------------------------

    @Test
    fun `all models provide bit-perfect headroom with zero clipping and zero limiter pumping`() {
        val models = AcousticModelCatalog.getModels()

        for (model in models) {
            for ((target, profile) in model.targetProfiles) {
                val peak = profile.computePeakBoostDb(points = 256)
                val totalGain = peak + profile.preampDb

                assertTrue(
                    "Profile ${model.id} / $target has peak=$peak dB, preamp=${profile.preampDb} dB, " +
                        "total=$totalGain dB. Must never exceed 0.005 dB to prevent clipping and limiter pumping!",
                    totalGain <= 0.005f
                )

                assertTrue(
                    "Profile ${model.id} / $target must report hasBitPerfectHeadroom() = true",
                    profile.hasBitPerfectHeadroom()
                )

                assertTrue(
                    "Pre-amp attenuation must be non-positive, got ${profile.preampDb}",
                    profile.preampDb <= 0.001f
                )
            }
        }
    }

    // ---- Filter Response & Conversion Tests --------------------------------

    @Test
    fun `biquad filter response matches theoretical expectations`() {
        val bell = BiquadBand(BiquadFilterType.PEAKING, 1000f, 6.0f, 1.414f)
        assertEquals("Peaking filter at center frequency must match gainDb", 6.0f, bell.responseAt(1000f), 0.1f)
        assertTrue("Peaking filter far away from center must approach 0 dB", abs(bell.responseAt(100f)) < 0.1f)
        assertTrue("Peaking filter far away from center must approach 0 dB", abs(bell.responseAt(10000f)) < 0.1f)

        val lowShelf = BiquadBand(BiquadFilterType.LOW_SHELF, 100f, 6.0f, 0.707f)
        assertEquals("Low shelf well below corner must match gainDb", 6.0f, lowShelf.responseAt(10f), 0.1f)
        assertEquals("Low shelf at corner must be approximately half gain in dB", 3.0f, lowShelf.responseAt(100f), 0.2f)
        assertTrue("Low shelf well above corner must approach 0 dB", abs(lowShelf.responseAt(1000f)) < 0.1f)

        val highShelf = BiquadBand(BiquadFilterType.HIGH_SHELF, 5000f, 6.0f, 0.707f)
        assertTrue("High shelf well below corner must approach 0 dB", abs(highShelf.responseAt(500f)) < 0.1f)
        assertEquals("High shelf at corner must be approximately half gain in dB", 3.0f, highShelf.responseAt(5000f), 0.2f)
        assertEquals("High shelf well above corner must match gainDb", 6.0f, highShelf.responseAt(20000f), 0.1f)
    }

    @Test
    fun `profile converts into valid BitChord EqCurve`() {
        val hd600 = AcousticModelCatalog.getModelById("sennheiser_hd600")!!
        val profile = hd600.activeProfile

        val eqCurve = profile.toEqCurve()
        assertNotNull(eqCurve)
        assertEquals(10, eqCurve.gainsDb.size)
        assertEquals(10, eqCurve.qs.size)
        assertTrue("Preamp in EqCurve must be <= 0 dB", eqCurve.preampDb <= 0f)

        val manualCurve = profile.toManualCurve()
        assertNotNull(manualCurve)
        assertEquals(10, manualCurve.gainsDb.size)
    }

    // ---- JSON Import & Export Tests ----------------------------------------

    @Test
    fun `catalog can be exported to JSON and reloaded without loss`() {
        val originalModels = AcousticModelCatalog.getModels()
        val json = AcousticModelCatalog.exportToJson(originalModels)
        assertTrue("Exported JSON must not be empty", json.isNotBlank())

        val decoded = AcousticModelCatalog.loadFromJson(json)
        assertEquals("Decoded models count must match original", originalModels.size, decoded.size)

        for (i in originalModels.indices) {
            val orig = originalModels[i]
            val dec = decoded[i]
            assertEquals(orig.id, dec.id)
            assertEquals(orig.name, dec.name)
            assertEquals(orig.brand, dec.brand)
            assertEquals(orig.type, dec.type)
            assertEquals(orig.preampDb, dec.preampDb, 0.01f)
            assertEquals(orig.biquadBands.size, dec.biquadBands.size)
        }
    }
}
