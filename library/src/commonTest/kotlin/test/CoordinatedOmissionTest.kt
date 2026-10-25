package test

import io.github.dsqrwym.hdrhistogram.Histogram
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 协调遗漏校正的语义测试（跨平台，JVM / JS / WasmJS 全跑）。
 *
 * 期望值全部由官方 Java 实现的行为推导：
 *   missingValue = value - interval
 *   while (missingValue >= interval) { record(missingValue); missingValue -= interval }
 */
class CoordinatedOmissionTest {

    private fun newHistogram() = Histogram(1L, 100_000_000L, 3)

    /**
     * value = 100, interval = 30 → 记录 100、70、40，不含 10
     */
    @Test
    fun testBackfillsDownToOneInterval() {
        val histogram = newHistogram()
        histogram.recordValueWithExpectedInterval(100L, 30L)

        assertEquals(3L, histogram.totalCount)
        assertEquals(1L, histogram.countAtValue(100L))
        assertEquals(1L, histogram.countAtValue(70L))
        assertEquals(1L, histogram.countAtValue(40L))
        assertEquals(0L, histogram.countAtValue(10L))
        assertEquals(0L, histogram.countAtValue(0L))
    }

    /**
     * 补点会参与 min/max 统计：最大值仍是原值，最小值落到最低的那个补点
     */
    @Test
    fun testBackfilledValuesAffectMinAndMax() {
        val histogram = newHistogram()
        histogram.recordValueWithExpectedInterval(100L, 30L)

        assertEquals(100L, histogram.maxValue)
        assertEquals(40L, histogram.minValue)
    }

    /**
     * 整除的情况：value = 100, interval = 25 → 100、75、50、25，共 4 条
     */
    @Test
    fun testExactMultipleOfInterval() {
        val histogram = newHistogram()
        histogram.recordValueWithExpectedInterval(100L, 25L)

        assertEquals(4L, histogram.totalCount)
        assertEquals(1L, histogram.countAtValue(25L))
        assertEquals(0L, histogram.countAtValue(0L))
        assertEquals(25L, histogram.minValue)
    }

    /**
     * 总记录数 = floor(value / interval)，但 value 小于一个间隔时仍会记录它自己
     */
    @Test
    fun testTotalCountIsValueDividedByInterval() {
        for (value in longArrayOf(100L, 101L, 999L, 1_000L)) {
            for (interval in longArrayOf(3L, 7L, 100L, 250L)) {
                val histogram = newHistogram()
                histogram.recordValueWithExpectedInterval(value, interval)
                assertEquals(
                    maxOf(1L, value / interval),
                    histogram.totalCount,
                    "value=$value interval=$interval"
                )
            }
        }
    }

    /**
     * 值不大于间隔时只记录它自己
     */
    @Test
    fun testValueNotLargerThanIntervalRecordsOnlyOnce() {
        val histogram = newHistogram()
        histogram.recordValueWithExpectedInterval(1_000L, 10_000L)

        assertEquals(1L, histogram.totalCount)
        assertEquals(1L, histogram.countAtValue(1_000L))
    }

    /**
     * 间隔非正数时完全关闭校正，只记录原值
     */
    @Test
    fun testNonPositiveIntervalDisablesCorrection() {
        val zero = newHistogram()
        zero.recordValueWithExpectedInterval(100L, 0L)
        assertEquals(1L, zero.totalCount)

        val negative = newHistogram()
        negative.recordValueWithExpectedInterval(100L, -5L)
        assertEquals(1L, negative.totalCount)
    }

    /**
     * 多次调用累加，同一个补点值会被累加到同一个槽
     */
    @Test
    fun testAccumulatesAcrossCalls() {
        val histogram = newHistogram()
        histogram.recordValueWithExpectedInterval(100L, 30L)
        histogram.recordValueWithExpectedInterval(100L, 30L)

        assertEquals(6L, histogram.totalCount)
        assertEquals(2L, histogram.countAtValue(40L))
    }

    /**
     * 补点后百分位应被拉低：单条 1000 时 p50 是 1000，
     * 校正后补出 990..10，p50 落到中段的 500。
     */
    @Test
    fun testPercentilesReflectBackfilledValues() {
        val histogram = Histogram(1L, 1_000_000L, 3)
        histogram.recordValueWithExpectedInterval(1_000L, 10L)

        assertEquals(100L, histogram.totalCount)
        assertEquals(1_000L, histogram.maxValue)
        assertEquals(10L, histogram.minValue)
        assertEquals(500L, histogram.valueAtPercentile(50.0))
    }

    /**
     * 官方 HistogramDataAccessTest.testGetStdDeviation 的第二个数据集：
     * 10000 个 1000（不足一个间隔，各 1 条）+ 1 个 1e8（补 9999 条），共 20000 条。
     * 官方用 0.1% 的相对容差。
     */
    @Test
    fun testOfficialCorrectedDatasetStatistics() {
        val histogram = Histogram(1L, 3_600_000_000L, 3)
        repeat(10_000) { histogram.recordValueWithExpectedInterval(1_000L, 10_000L) }
        histogram.recordValueWithExpectedInterval(100_000_000L, 10_000L)

        assertEquals(20_000L, histogram.totalCount)

        val expectedMean = (1_000.0 + 50_000_000.0) / 2
        var squareDeviationSum = 10_000 * (1_000.0 - expectedMean) * (1_000.0 - expectedMean)
        var value = 10_000L
        while (value <= 100_000_000L) {
            squareDeviationSum += (value - expectedMean) * (value - expectedMean)
            value += 10_000L
        }
        val expectedStdDev = sqrt(squareDeviationSum / 20_000)

        assertEquals(expectedMean, histogram.mean, expectedMean * 0.001)
        assertEquals(expectedStdDev, histogram.stdDeviation, expectedStdDev * 0.001)
    }
}
