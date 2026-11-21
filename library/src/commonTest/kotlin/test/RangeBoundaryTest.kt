package test

import io.github.dsqrwym.hdrhistogram.AtomicHistogram
import io.github.dsqrwym.hdrhistogram.Histogram
import io.github.dsqrwym.hdrhistogram.Recorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 记录上界、countAtValue 越界、valueAtPercentile 越界的契约测试。
 *
 * 布局前提：(lowest = 1, highest = 1000, 3 位精度) 的 counts 数组长 2048，
 * highestRecordableValue = 2047，因此 (1000, 2047] 是“参数外但物理可记录”的区间。
 */
class RangeBoundaryTest {

    private fun h() = Histogram(1L, 1_000L, 3)
    private fun ah() = AtomicHistogram(1L, 1_000L, 3)

    @Test
    fun layoutAssumptions() {
        val histogram = h()
        assertEquals(2_047L, histogram.highestRecordableValue)
        assertTrue(histogram.highestRecordableValue > histogram.highestTrackableValue)
    }

    @Test
    fun recordValueAcceptsLastBucketAboveHighestTrackable() {
        val histogram = h()
        val last = histogram.highestRecordableValue

        histogram.recordValue(last)

        assertEquals(1L, histogram.totalCount)
        assertEquals(last, histogram.maxValue)
        assertEquals(1L, histogram.countAtValue(last))
    }

    @Test
    fun recordValueAcceptsGapValues() {
        val histogram = h()
        val gap = histogram.highestTrackableValue + 1L

        histogram.recordValue(gap)

        assertEquals(1L, histogram.totalCount)
        assertEquals(gap, histogram.maxValue)
        assertEquals(1L, histogram.countAtValue(gap))
    }

    @Test
    fun recordValueRejectsBeyondLastBucket() {
        val histogram = h()
        assertFailsWith<IllegalArgumentException> {
            histogram.recordValue(histogram.highestRecordableValue + 1L)
        }
    }

    @Test
    fun recordValueStillRejectsNegativeAndNonPositiveCount() {
        val histogram = h()
        assertFailsWith<IllegalArgumentException> { histogram.recordValue(-1L) }
        assertFailsWith<IllegalArgumentException> { histogram.recordValue(1L, 0L) }
    }

    @Test
    fun countAtValueReturnsRealCountInGapAndZeroBeyond() {
        val histogram = h()
        val gap = histogram.highestTrackableValue + 1L
        histogram.recordValue(gap)

        assertEquals(1L, histogram.countAtValue(gap))
        // 2047 槽没有记录过，不能因为“clamp 到末槽”而返回 gap 的计数。
        assertEquals(0L, histogram.countAtValue(histogram.highestRecordableValue))
        assertEquals(0L, histogram.countAtValue(histogram.highestRecordableValue + 1L))
        assertEquals(0L, histogram.countAtValue(Long.MAX_VALUE))
    }

    @Test
    fun countAtValueRejectsNegative() {
        val histogram = h()
        assertFailsWith<IllegalArgumentException> { histogram.countAtValue(-1L) }
    }

    @Test
    fun valueAtPercentileClampsLikeMajority() {
        // 用较粗的分辨率区分 lowest / highest：5000 所在区间是 [5000, 5003]。
        val histogram = Histogram(1L, 3_600_000_000L, 3)
        histogram.recordValue(5_000L)

        assertEquals(5_000L, histogram.valueAtPercentile(0.0))
        assertEquals(5_003L, histogram.valueAtPercentile(-1.0))
        assertEquals(histogram.valueAtPercentile(0.0), histogram.valueAtPercentile(-0.0))
        assertEquals(
            histogram.valueAtPercentile(100.0),
            histogram.valueAtPercentile(100.5),
        )
        assertEquals(
            histogram.valueAtPercentile(100.0),
            histogram.valueAtPercentile(1_000.0),
        )
    }

    @Test
    fun emptyHistogramStillReturnsZeroForOutOfRangePercentile() {
        val histogram = h()
        assertEquals(0L, histogram.valueAtPercentile(0.0))
        assertEquals(0L, histogram.valueAtPercentile(-1.0))
        assertEquals(0L, histogram.valueAtPercentile(100.5))
    }

    @Test
    fun valueAtPercentileRejectsNaN() {
        val histogram = h()
        assertFailsWith<IllegalArgumentException> { histogram.valueAtPercentile(Double.NaN) }
    }

    @Test
    fun percentileRankUsesCeilLikeJava() {
        val histogram = h()
        for (value in 1L..7L) histogram.recordValue(value)

        // 7 * 20% = 1.4 -> Java 的 ceil 得 2；C/.NET/Python 的 round-half-up 会得 1。
        assertEquals(2L, histogram.valueAtPercentile(20.0))
    }

    @Test
    fun addAcceptsGapValuesThatRecordValueNowAccepts() {
        val source = h()
        source.recordValue(1_001L)

        val target = h()
        target.add(source)

        assertEquals(1L, target.totalCount)
        assertEquals(1L, target.countAtValue(1_001L))
    }

    @Test
    fun structuralEqualsCoversGapValuesAcrossLayouts() {
        val wide = Histogram(1L, 10_000L, 3)
        val narrow = Histogram(1L, 1_000L, 3)

        // 1500 在 narrow 的“参数外但物理可记录”区间内。
        wide.recordValue(1_500L)
        narrow.recordValue(1_500L)

        assertEquals(wide, narrow)
        assertEquals(narrow, wide)
    }

    @Test
    fun recorderAcceptsGapValues() {
        val recorder = Recorder(1L, 1_000L, 3)
        recorder.recordValue(1_001L)

        val snapshot = recorder.getIntervalHistogram()

        assertEquals(1L, snapshot.totalCount)
        assertEquals(1L, snapshot.countAtValue(1_001L))
    }

    @Test
    fun atomicHistogramMatchesHistogram() {
        val plain = h()
        val atomic = ah()
        val values = longArrayOf(1L, 1_000L, 1_001L, 2_047L)

        for (value in values) {
            plain.recordValue(value)
            atomic.recordValue(value)
        }

        assertEquals(plain.totalCount, atomic.totalCount)
        assertEquals(plain.maxValue, atomic.maxValue)
        assertEquals(plain.valueAtPercentile(-1.0), atomic.valueAtPercentile(-1.0))
        assertEquals(plain.valueAtPercentile(100.5), atomic.valueAtPercentile(100.5))
        assertEquals(plain.countAtValue(2_048L), atomic.countAtValue(2_048L))
        assertEquals(0L, atomic.countAtValue(2_048L))
        assertFailsWith<IllegalArgumentException> { atomic.countAtValue(-1L) }
    }
}