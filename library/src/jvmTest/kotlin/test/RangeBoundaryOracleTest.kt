package test

import io.github.dsqrwym.hdrhistogram.AtomicHistogram
import io.github.dsqrwym.hdrhistogram.Histogram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 与官方 Java 2.2.2 的边界对拍。
 *
 * 对拍范围：
 *  - (highestTrackableValue, highestRecordableValue] 的记录行为；
 *  - 越界百分位的 clamp 行为；
 *  - countAtValue 的“末槽之后”行为差异（有意不同，单列断言）。
 */
class RangeBoundaryOracleTest {

    private fun kt() = Histogram(1L, 1_000L, 3)
    private fun java() = org.HdrHistogram.Histogram(1L, 1_000L, 3)

    @Test
    fun lastBucketRecordMatchesOfficial() {
        val k = kt()
        val j = java()
        val last = k.highestRecordableValue

        k.recordValue(last)
        j.recordValue(last)

        assertEquals(j.totalCount, k.totalCount)
        assertEquals(j.maxValue, k.maxValue)
        assertEquals(j.minNonZeroValue, k.minNonZeroValue)
        assertEquals(j.getCountAtValue(last), k.countAtValue(last))
        assertEquals(j.getValueAtPercentile(100.0), k.valueAtPercentile(100.0))
    }

    @Test
    fun gapValuesRecordedLikeOfficial() {
        val k = kt()
        val j = java()
        val gap = k.highestTrackableValue + 1L

        repeat(100) { offset ->
            val value = gap + offset
            k.recordValue(value)
            j.recordValue(value)
        }

        assertEquals(j.totalCount, k.totalCount)
        assertEquals(j.maxValue, k.maxValue)
        assertEquals(j.minNonZeroValue, k.minNonZeroValue)
        assertEquals(j.getValueAtPercentile(99.0), k.valueAtPercentile(99.0))
        assertEquals(j.getCountAtValue(gap), k.countAtValue(gap))
    }

    @Test
    fun beyondLastBucketBothRejectRecording() {
        val k = kt()
        val j = java()
        val beyond = k.highestRecordableValue + 1L

        assertFailsWith<IllegalArgumentException> { k.recordValue(beyond) }
        assertFailsWith<ArrayIndexOutOfBoundsException> { j.recordValue(beyond) }
    }

    @Test
    fun outOfRangePercentilesMatchOfficial() {
        val k = kt()
        val j = java()
        for (value in 1L..100L) {
            k.recordValue(value)
            j.recordValue(value)
        }

        for (percentile in doubleArrayOf(-1.0, -0.0, 0.0, 100.0, 100.5, 1_000.0)) {
            assertEquals(
                j.getValueAtPercentile(percentile),
                k.valueAtPercentile(percentile),
                "percentile=$percentile",
            )
        }
    }

    @Test
    fun negativePercentileUsesHighestOfFirstBucketLikeOfficial() {
        val k = Histogram(1L, 3_600_000_000L, 3)
        val j = org.HdrHistogram.Histogram(1L, 3_600_000_000L, 3)
        k.recordValue(5_000L)
        j.recordValue(5_000L)

        assertEquals(5_000L, k.valueAtPercentile(0.0))
        assertEquals(5_003L, k.valueAtPercentile(-1.0))
        assertEquals(j.getValueAtPercentile(0.0), k.valueAtPercentile(0.0))
        assertEquals(j.getValueAtPercentile(-1.0), k.valueAtPercentile(-1.0))
    }

    @Test
    fun countBeyondLastSlotIsIntentionalDivergence() {
        val k = kt()
        val j = java()
        val last = k.highestRecordableValue

        k.recordValue(last)
        j.recordValue(last)

        val beyond = last + 1L

        // 本库：末槽之后返回 0（数学正确，与 C 一致）。
        assertEquals(0L, k.countAtValue(beyond))

        // 官方 Java：下标 clamp 到末槽，因此返回末槽的计数。
        assertEquals(1L, j.getCountAtValue(beyond))
    }

    @Test
    fun atomicHistogramMatchesOfficial() {
        val k = AtomicHistogram(1L, 1_000L, 3)
        val j = java()
        val last = k.highestRecordableValue

        k.recordValue(last)
        j.recordValue(last)

        assertEquals(j.totalCount, k.totalCount)
        assertEquals(j.maxValue, k.maxValue)
        assertEquals(j.getCountAtValue(last), k.countAtValue(last))
        assertEquals(j.getValueAtPercentile(100.5), k.valueAtPercentile(100.5))
    }
}