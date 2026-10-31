package test

import io.github.dsqrwym.hdrhistogram.AtomicHistogram
import io.github.dsqrwym.hdrhistogram.Histogram
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalAtomicApi::class)
class AddTest {

    private fun newHistogram(highest: Long = 1_000_000L) = Histogram(1L, highest, 3)

    @Test
    fun addSameLayoutSumsCounts() {
        val target = newHistogram()
        val source = newHistogram()
        target.recordValue(100L, 2L)
        source.recordValue(100L, 3L)
        source.recordValue(5_000L)

        target.add(source)

        assertEquals(6L, target.totalCount)
        assertEquals(5L, target.countAtValue(100L))
        assertEquals(1L, target.countAtValue(5_000L))
    }

    @Test
    fun addIntoEmptyHistogram() {
        val target = newHistogram()
        val source = newHistogram()
        repeat(100) { source.recordValue(it * 100L + 1) }

        target.add(source)

        assertEquals(source.totalCount, target.totalCount)
        assertEquals(source.maxValue, target.maxValue)
        assertEquals(source.minValue, target.minValue)
        assertEquals(source.minNonZeroValue, target.minNonZeroValue)
        for (i in 0 until target.countsArrayLength) {
            assertEquals(source.countAtIndex(i), target.countAtIndex(i), "slot $i")
        }
    }

    @Test
    fun addEmptyHistogramIsNoOp() {
        val target = newHistogram()
        target.recordValue(42L)
        val beforeMax = target.maxValue
        val beforeMin = target.minNonZeroValue

        target.add(newHistogram())

        assertEquals(1L, target.totalCount)
        assertEquals(beforeMax, target.maxValue)
        assertEquals(beforeMin, target.minNonZeroValue)
    }

    @Test
    fun addMergesMinAndMax() {
        val target = newHistogram()
        val source = newHistogram()
        target.recordValue(10_000L)
        source.recordValue(10L)
        source.recordValue(500_000L)

        target.add(source)

        assertEquals(10L, target.minValue)
        assertEquals(source.minNonZeroValue, target.minNonZeroValue)
        assertTrue(target.maxValue >= 500_000L)
    }

    @Test
    fun addDifferentLayoutRecordsByValue() {
        val target = newHistogram(10_000_000L)
        val source = newHistogram(1_000_000L)
        repeat(1_000) { source.recordValue(it * 900L + 1) }

        target.add(source)

        assertEquals(source.totalCount, target.totalCount)
        // 两者 unitMagnitude 与 subBucketCount 相同，同一值的槽位完全一致，
        // 因此合并后源长度范围内的每个槽都必须与源逐槽相等。
        for (i in 0 until source.countsArrayLength) {
            assertEquals(source.countAtIndex(i), target.countAtIndex(i), "slot $i")
        }
    }

    @Test
    fun addRejectsValuesBeyondRange() {
        val target = newHistogram(1_000_000L)
        val source = newHistogram(10_000_000L)
        source.recordValue(5_000_000L)

        assertFailsWith<IllegalArgumentException> {
            target.add(source)
        }
    }

    @Test
    fun addTotalCountMatchesSumOfSlots() {
        val target = newHistogram()
        val source = newHistogram()
        repeat(200) { source.recordValue(it * 1_000L + 1, 5L) }

        target.add(source)

        var sum = 0L
        for (i in 0 until target.countsArrayLength) sum += target.countAtIndex(i)
        assertEquals(1_000L, target.totalCount)
        assertEquals(target.totalCount, sum)
    }

    @Test
    fun addHandlesDataBelowLowestDiscernible() {
        val target = Histogram(1_000L, 1_000_000L, 3)
        val source = Histogram(1_000L, 1_000_000L, 3)
        source.recordValue(500L)

        target.add(source)

        assertEquals(1L, target.totalCount)
        assertEquals(0L, target.minValue)
    }

    @Test
    fun addSelfDoublesCounts() {
        val histogram = newHistogram()
        repeat(100) { histogram.recordValue(it * 100L + 1) }
        val expectedTotal = histogram.totalCount
        val expectedMax = histogram.maxValue
        val before = LongArray(histogram.countsArrayLength) { histogram.countAtIndex(it) }

        histogram.add(histogram)

        assertEquals(expectedTotal * 2, histogram.totalCount)
        assertEquals(expectedMax, histogram.maxValue)
        for (i in 0 until histogram.countsArrayLength) {
            assertEquals(before[i] * 2, histogram.countAtIndex(i), "slot $i")
        }
    }

    /**
     * 目标最高可记录值（highestRecordableValue）大于 highestTrackableValue。
     * 源的最大值落在两者之间时，异构合并必须成功，不能因 recordValue 的上界校验而抛异常。
     */
    @Test
    fun addAcceptsValuesWithinLastBucket() {
        val target = Histogram(1L, 1_000_000L, 3)
        val source = Histogram(1L, 2_000_000L, 3)
        source.recordValue(1_020_000L)

        assertFalse(target.hasSameLayoutAs(source))
        assertTrue(source.maxValue in (target.highestTrackableValue + 1)..target.highestRecordableValue)

        target.add(source)

        assertEquals(1L, target.totalCount)
        assertEquals(1_020_415L, target.maxValue)
        assertEquals(1_019_904L, target.minNonZeroValue)
        assertEquals(1L, target.countAtIndex(target.countsArrayIndex(1_019_904L)))
    }

    @Test
    fun atomicAddAcceptsValuesWithinLastBucket() {
        val target = AtomicHistogram(1L, 1_000_000L, 3)
        val source = Histogram(1L, 2_000_000L, 3)
        source.recordValue(1_020_000L)

        assertFalse(target.hasSameLayoutAs(source))
        assertTrue(source.maxValue in (target.highestTrackableValue + 1)..target.highestRecordableValue)

        target.add(source)

        assertEquals(1L, target.totalCount)
        assertEquals(1_020_415L, target.maxValue)
        assertEquals(1_019_904L, target.minNonZeroValue)
        assertEquals(1L, target.countAtIndex(target.countsArrayIndex(1_019_904L)))
    }
}
