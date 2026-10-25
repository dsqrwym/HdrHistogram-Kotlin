package test

import io.github.dsqrwym.hdrhistogram.Histogram
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 标准差与中值口径的语义测试（跨平台，JS / Wasm / Native / JVM 全跑）。
 *
 * 期望值来源与官方 HistogramDataAccessTest.testGetStdDeviation 一致：
 * 对“原始数据”直接套解析公式，再用 0.1% 的相对容差比较，
 * 用于验证量化误差处于直方图设计精度之内。
 */
class StdDeviationTest {

    private val highest = 3_600_000_000L
    private val digits = 3

    private fun newHistogram() = Histogram(1L, highest, digits)

    /**
     * 复刻官方 testGetStdDeviation 的 rawHistogram 数据集：
     *   10000 个 1000 + 1 个 100000000 = 10001 条
     *
     * 官方用 recordValue 循环 10000 次，这里用 count 形式等价表达。
     */
    @Test
    fun testMatchesOfficialAnalyticExpectation() {
        val histogram = newHistogram()
        histogram.recordValue(1000L, 10_000L)
        histogram.recordValue(100_000_000L)

        assertEquals(10_001L, histogram.totalCount)

        // 直接对原始数据求均值与标准差（与官方测试同样的式子）
        val expectedMean = (10_000.0 * 1000 + 1.0 * 100_000_000) / 10_001
        val expectedStdDev = sqrt(
            (10_000.0 * (1000.0 - expectedMean).pow(2) +
                    (100_000_000.0 - expectedMean).pow(2)) / 10_001
        )

        // 官方同样使用 0.1% 的相对容差
        assertEquals(expectedMean, histogram.mean, expectedMean * 0.001)
        assertEquals(expectedStdDev, histogram.stdDeviation, expectedStdDev * 0.001)
    }

    /**
     * 手算可核验的用例（无容差，精确比较）：
     *   样本 1000 × 1、5000 × 3
     *   代表值 1000 与 5002  →  mean = 4001.5
     *   方差 = (3001.5² + 3 × 1000.5²) / 4 = 3_003_000.75
     */
    @Test
    fun testHandComputedExample() {
        val histogram = newHistogram()
        histogram.recordValue(1000L, 1L)
        histogram.recordValue(5000L, 3L)

        assertEquals(4001.5, histogram.mean)
        assertEquals(sqrt(3_003_000.75), histogram.stdDeviation)
    }

    /**
     * 中值口径：必须返回区间内的整数。
     * 同时锁死“Double 中值”这个 bug 不会回归。
     */
    @Test
    fun testMedianEquivalentValueIsInteger() {
        val histogram = newHistogram()

        // lowest = 1 时第 0 桶宽度为 1，区间内只有它自己
        assertEquals(1000L, histogram.medianEquivalentValue(1000L))
        // 桶宽 4：[5000, 5003]，中值 = 5000 + (4 ushr 1) = 5002
        assertEquals(5002L, histogram.medianEquivalentValue(5000L))
        assertEquals(5002L, histogram.medianEquivalentValue(5003L))
    }

    /**
     * 回归测试：桶宽为 1 时，均值不得出现 +0.5 的系统性偏差。
     *
     * 1000 与 1001 各一个，正确均值是 1000.5。
     * 若中值退回 `size / 2.0`，代表值会变成 1000.5 与 1001.5，均值变成 1001.0。
     */
    @Test
    fun testNoHalfUnitBiasForUnitWidthBuckets() {
        val histogram = newHistogram()
        histogram.recordValue(1000L, 1L)
        histogram.recordValue(1001L, 1L)

        assertEquals(1000.5, histogram.mean)
        assertEquals(0.5, histogram.stdDeviation)
    }

    /**
     * 边界：空直方图、单值、两个等距值
     */
    @Test
    fun testEdgeCases() {
        val empty = newHistogram()
        assertEquals(0.0, empty.mean)
        assertEquals(0.0, empty.stdDeviation)

        val single = newHistogram()
        single.recordValue(42_000L, 7L)
        assertEquals(0.0, single.stdDeviation)

        val two = newHistogram()
        two.recordValue(1000L, 1L)
        two.recordValue(5000L, 1L)
        // 代表值 1000 与 5002 → 均值 3001，标准差 = (5002 - 1000) / 2
        assertEquals(3001.0, two.mean)
        assertEquals(2001.0, two.stdDeviation)
    }

    /**
     * 基本性质：标准差非负，且不会超过值域宽度
     */
    @Test
    fun testBasicInvariants() {
        val histogram = newHistogram()
        histogram.recordValue(0L, 1L)
        histogram.recordValue(1_000_000L, 1L)
        histogram.recordValue(2_000_000L, 3L)

        assertTrue(histogram.stdDeviation >= 0.0, "standard deviation must be non-negative")
        assertTrue(
            histogram.stdDeviation <= (histogram.maxValue - histogram.minValue).toDouble(),
            "standard deviation must not exceed the value range width"
        )
    }
}
