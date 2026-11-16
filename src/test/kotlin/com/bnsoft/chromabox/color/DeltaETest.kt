package com.bnsoft.chromabox.color

import org.junit.Assert.assertEquals
import org.junit.Test

class DeltaETest {
    @Test
    fun `matches Sharma's CIEDE2000 test data`() {
        // Pairs from Sharma, Wu and Dalal (2005), table 1: Lab 1, Lab 2, expected difference.
        val pairs = listOf(
            Triple(Lab(50.0, 2.6772, -79.7751), Lab(50.0, 0.0, -82.7485), 2.0425),
            Triple(Lab(50.0, 3.1571, -77.2803), Lab(50.0, 0.0, -82.7485), 2.8615),
            Triple(Lab(50.0, 2.8361, -74.0200), Lab(50.0, 0.0, -82.7485), 3.4412),
            Triple(Lab(50.0, 0.0, 0.0), Lab(50.0, -1.0, 2.0), 2.3669),
            Triple(Lab(50.0, -1.0, 2.0), Lab(50.0, 0.0, 0.0), 2.3669),
            Triple(Lab(50.0, 2.5, 0.0), Lab(73.0, 25.0, -18.0), 27.1492),
            Triple(Lab(50.0, 2.5, 0.0), Lab(61.0, -5.0, 29.0), 22.8977),
            Triple(Lab(50.0, 2.5, 0.0), Lab(56.0, -27.0, -3.0), 31.9030),
            Triple(Lab(50.0, 2.5, 0.0), Lab(58.0, 24.0, 15.0), 19.4535),
            Triple(Lab(50.0, 2.5, 0.0), Lab(50.0, 3.1736, 0.5854), 1.0000),
            Triple(Lab(60.2574, -34.0099, 36.2677), Lab(60.4626, -34.1751, 39.4387), 1.2644),
            Triple(Lab(63.0109, -31.0961, -5.8663), Lab(62.8187, -29.7946, -4.0864), 1.2630),
            Triple(Lab(2.0776, 0.0795, -1.1350), Lab(0.9033, -0.0636, -0.5514), 0.9082),
        )
        for ((a, b, expected) in pairs) assertEquals("$a $b", expected, DeltaE.ciede2000(a, b), 1e-4)
    }

    @Test
    fun `same color is 0, and it's symmetric`() {
        val red = ChromaColor(1.0, 0.0, 0.0)
        val almost = ChromaColor.fromRgb255(254, 2, 0)
        assertEquals(0.0, DeltaE.ciede2000(red, red), 0.0)
        assertEquals(DeltaE.ciede2000(red, almost), DeltaE.ciede2000(almost, red), 1e-12)
    }
}
