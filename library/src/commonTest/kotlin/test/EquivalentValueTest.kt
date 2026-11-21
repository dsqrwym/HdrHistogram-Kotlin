package test

import io.github.dsqrwym.hdrhistogram.Histogram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `nextNonEquivalentValue` / `valuesAreEquivalent` 的契约测试。
 */
class EquivalentValueTest {

    private val fine = Histogram(1L, 3_600_000_000L, 3)
    private val coarse = Histogram(1L, 1_000L, 3)

    @Test
    fun nextNonEquivalentValueIsHighestPlusOne() {
        // (1, 3.6e9, 3)：5000 所在区间是 [5000, 5003]
        assertEquals(5_004L, fine.nextNonEquivalentValue(5_000L))
        assertEquals(5_003L, fine.highestEquivalentValue(5_000L))
        assertEquals(fine.highestEquivalentValue(5_000L) + 1L, fine.nextNonEquivalentValue(5_000L))
    }

    @Test
    fun valuesAreEquivalentUsesLowestRange() {
        assertTrue(fine.valuesAreEquivalent(5_000L, 5_003L))
        assertFalse(fine.valuesAreEquivalent(5_000L, 5_004L))
        assertTrue(fine.valuesAreEquivalent(5_003L, 5_000L))
    }

    @Test
    fun zeroValueIsEquivalentOnlyToItself() {
        assertTrue(fine.valuesAreEquivalent(0L, 0L))
        assertFalse(fine.valuesAreEquivalent(0L, 1L))
    }

    @Test
    fun bucketBoundaryUsesNewRange() {
        // (1, 1000, 3)：2047 是 bucket 0（range 1），2048 是 bucket 1（range 2）
        assertFalse(coarse.valuesAreEquivalent(2_047L, 2_048L))
        assertTrue(coarse.valuesAreEquivalent(2_048L, 2_049L))
        assertEquals(2_050L, coarse.nextNonEquivalentValue(2_048L))
    }
}