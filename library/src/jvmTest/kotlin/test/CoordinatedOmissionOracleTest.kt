package test

import io.github.dsqrwym.hdrhistogram.AtomicHistogram
import io.github.dsqrwym.hdrhistogram.Histogram
import io.github.dsqrwym.hdrhistogram.Recorder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 与官方 Java 实现的协调遗漏补点逐位对拍（容差 0）。
 *
 * 只要补点的终止条件（>= interval 还是 > interval）、递减步长或写入顺序与官方有任何差异，
 * totalCount、min/max、mean、stdDeviation 或某个计数槽就会立刻不相等。
 */
class CoordinatedOmissionOracleTest {

    private val lowest = 1L
    private val highest = 10_000_000L
    private val digits = 3

    @Test
    fun testRandomValuesBackfillIdentically() {
        val kt = Histogram(lowest, highest, digits)
        val official = org.HdrHistogram.Histogram(lowest, highest, digits)
        val random = Random(20261025)

        repeat(200) {
            val value = random.nextLong(1L, highest)
            val interval = random.nextLong(1L, 100_000L)
            kt.recordValueWithExpectedInterval(value, interval)
            official.recordValueWithExpectedInterval(value, interval)
        }

        assertEquals(official.getTotalCount(), kt.totalCount)
        assertEquals(official.getMinValue(), kt.minValue)
        assertEquals(official.getMaxValue(), kt.maxValue)
        assertEquals(official.getMean(), kt.mean)
        assertEquals(official.getStdDeviation(), kt.stdDeviation)
    }

    /**
     * 逐槽对拍。
     *
     * 官方的 getCountAtIndex 是包私有，只能用公开的 getCountAtValue(value)；
     * 这里借本库的 valueFromIndex 解出每个槽的区间下界（两边布局已由标准差对拍验证一致）。
     */
    @Test
    fun testCountsMatchOfficialSlotBySlot() {
        val kt = Histogram(lowest, highest, digits)
        val official = org.HdrHistogram.Histogram(lowest, highest, digits)

        val samples = longArrayOf(100L, 999_999L, 1_000_000L)
        val intervals = longArrayOf(30L, 1L, 7L)

        for (i in samples.indices) {
            kt.recordValueWithExpectedInterval(samples[i], intervals[i])
            official.recordValueWithExpectedInterval(samples[i], intervals[i])
        }

        for (index in 0 until kt.countsArrayLength) {
            val value = kt.valueFromIndex(index)
            assertEquals(
                official.getCountAtValue(value),
                kt.countAtIndex(index),
                "counts[$index] (value=$value)"
            )
        }
    }

    /**
     * 官方 HistogramDataAccessTest.testGetStdDeviation 的第二个数据集，精确对拍
     */
    @Test
    fun testOfficialCorrectedDataset() {
        val kt = Histogram(1L, 3_600_000_000L, digits)
        val official = org.HdrHistogram.Histogram(1L, 3_600_000_000L, digits)

        repeat(10_000) {
            kt.recordValueWithExpectedInterval(1_000L, 10_000L)
            official.recordValueWithExpectedInterval(1_000L, 10_000L)
        }
        kt.recordValueWithExpectedInterval(100_000_000L, 10_000L)
        official.recordValueWithExpectedInterval(100_000_000L, 10_000L)

        assertEquals(20_000L, kt.totalCount)
        assertEquals(official.getTotalCount(), kt.totalCount)
        assertEquals(official.getMinValue(), kt.minValue)
        assertEquals(official.getMaxValue(), kt.maxValue)
        assertEquals(official.getMean(), kt.mean)
        assertEquals(official.getStdDeviation(), kt.stdDeviation)
    }

    @Test
    fun testAtomicHistogramMatchesOfficial() {
        val kt = AtomicHistogram(lowest, highest, digits)
        val official = org.HdrHistogram.AtomicHistogram(lowest, highest, digits)

        repeat(100) {
            kt.recordValueWithExpectedInterval(1_000_000L, 1_000L)
            official.recordValueWithExpectedInterval(1_000_000L, 1_000L)
        }

        assertEquals(official.getTotalCount(), kt.totalCount)
        assertEquals(official.getMinValue(), kt.minValue)
        assertEquals(official.getMaxValue(), kt.maxValue)
        assertEquals(official.getMean(), kt.mean)
        assertEquals(official.getStdDeviation(), kt.stdDeviation)
    }

    @Test
    fun testRecorderMatchesOfficial() {
        val kt = Recorder(lowest, highest, digits)
        val official = org.HdrHistogram.Recorder(lowest, highest, digits)

        repeat(100) {
            kt.recordValueWithExpectedInterval(1_000_000L, 1_000L)
            official.recordValueWithExpectedInterval(1_000_000L, 1_000L)
        }

        val ktInterval = kt.getIntervalHistogram()
        val officialInterval = official.getIntervalHistogram()

        assertEquals(officialInterval.getTotalCount(), ktInterval.totalCount)
        assertEquals(officialInterval.getMinValue(), ktInterval.minValue)
        assertEquals(officialInterval.getMaxValue(), ktInterval.maxValue)
        assertEquals(officialInterval.getMean(), ktInterval.mean)
        assertEquals(officialInterval.getStdDeviation(), ktInterval.stdDeviation)
    }

    private val threadMXBean = java.lang.management.ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean

    private inline fun measureAllocatedBytes(block: () -> Unit): Long {
        val threadId = Thread.currentThread().id
        val before = threadMXBean.getThreadAllocatedBytes(threadId)
        block()
        return threadMXBean.getThreadAllocatedBytes(threadId) - before
    }

    /**
     * 补点循环必须零分配：inline 回调不能退化成 Function 对象。
     * 每次调用写 10 个值（value / interval = 10），两个测量窗口只断言第二个。
     */
    @Test
    fun testZeroAllocation() {
        val histogram = Histogram(lowest, highest, digits)
        repeat(20_000) { histogram.recordValueWithExpectedInterval(10_000L, 1_000L) }

        val iterations = 10_000
        val firstWindow = measureAllocatedBytes {
            repeat(iterations) { histogram.recordValueWithExpectedInterval(10_000L, 1_000L) }
        }
        val secondWindow = measureAllocatedBytes {
            repeat(iterations) { histogram.recordValueWithExpectedInterval(10_000L, 1_000L) }
        }

        println("recordValueWithExpectedInterval allocation -- window 1: $firstWindow B, window 2: $secondWindow B")
        assertEquals(0L, secondWindow, "correction path must be allocation free (window 2: $secondWindow B)")
    }
}
