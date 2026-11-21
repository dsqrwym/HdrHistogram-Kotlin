package test

import io.github.dsqrwym.hdrhistogram.Histogram
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `copy()` / `copyInto()` / 等价助手与官方 Java 2.2.2 的对拍。
 */
class CopyOracleTest {

    private fun nextValue(seed: Long): Long =
        (seed * 6364136223846793005L + 1442695040888963407L) ushr 33

    private fun assertSameHistogram(
        expected: org.HdrHistogram.Histogram,
        actual: Histogram,
    ) {
        assertEquals(expected.totalCount, actual.totalCount)
        assertEquals(expected.maxValue, actual.maxValue)
        assertEquals(expected.minValue, actual.minValue)
        assertEquals(expected.minNonZeroValue, actual.minNonZeroValue)

        for (i in 0 until actual.countsArrayLength) {
            val value = actual.valueFromIndex(i)
            if (value > actual.highestTrackableValue) continue
            assertEquals(expected.getCountAtValue(value), actual.countAtIndex(i), "slot $i / value $value")
        }
    }

    @Test
    fun copyMatchesOfficial() {
        val k = Histogram(1L, 1_000_000L, 3)
        val j = org.HdrHistogram.Histogram(1L, 1_000_000L, 3)

        var seed = 42L
        repeat(5_000) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val value = nextValue(seed) % 1_000_000L
            k.recordValue(value)
            j.recordValue(value)
        }

        val kCopy = k.copy()
        val jCopy = j.copy()

        assertSameHistogram(jCopy, kCopy)
        assertEquals(jCopy.getValueAtPercentile(99.0), kCopy.valueAtPercentile(99.0))
    }

    @Test
    fun copyIntoMatchesOfficial() {
        val kSource = Histogram(1L, 1_000_000L, 3)
        val jSource = org.HdrHistogram.Histogram(1L, 1_000_000L, 3)
        val kTarget = Histogram(1L, 1_000_000L, 3)
        val jTarget = org.HdrHistogram.Histogram(1L, 1_000_000L, 3)

        kTarget.recordValue(999_999L)
        jTarget.recordValue(999_999L)

        var seed = 7L
        repeat(1_000) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val value = nextValue(seed) % 1_000_000L
            kSource.recordValue(value)
            jSource.recordValue(value)
        }

        kSource.copyInto(kTarget)
        jSource.copyInto(jTarget)

        assertSameHistogram(jTarget, kTarget)
    }

    @Test
    fun equivalenceHelpersMatchOfficial() {
        val k = Histogram(1L, 1_000_000L, 3)
        val j = org.HdrHistogram.Histogram(1L, 1_000_000L, 3)

        var seed = 123L
        repeat(10_000) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val value = nextValue(seed) % 1_000_000L
            assertEquals(j.nextNonEquivalentValue(value), k.nextNonEquivalentValue(value), "value=$value")
        }

        var seedA = 456L
        var seedB = 789L
        repeat(10_000) {
            seedA = seedA * 6364136223846793005L + 1442695040888963407L
            seedB = seedB * 6364136223846793005L + 1442695040888963407L
            val a = nextValue(seedA) % 1_000_000L
            val b = nextValue(seedB) % 1_000_000L
            assertEquals(j.valuesAreEquivalent(a, b), k.valuesAreEquivalent(a, b), "a=$a b=$b")
        }
    }
}