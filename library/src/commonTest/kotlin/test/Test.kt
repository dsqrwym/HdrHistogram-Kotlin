package test

import io.github.dsqrwym.hdrhistogram.Histogram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Test {
    @Test
    fun testHistogram() {
        val histogram = Histogram(1L, 1_000_000L, 3)

        histogram.recordValue(10)
        histogram.recordValue(12_345)

        println(histogram.totalCount)             // 2
        println(histogram.valueAtPercentile(0.0)) // 10
        println(histogram.valueAtPercentile(50.0)) // 10
        println(histogram.valueAtPercentile(100.0)) // 12351
    }

    @Test
    fun testLinearDataLoop() {
        val histogram = Histogram(1L, 10_000_000L, 3)
        val limit = 1_000_000L

        // 循环录入 1 到 1,000,000
        for (i in 1L..limit) {
            histogram.recordValue(i)
        }

        assertEquals(limit, histogram.totalCount, "总计数必须匹配")
        assertTrue(histogram.minValue <= 1L, "最小值应该接近或等于 1")

        // Histogram 的核心特性：允许一定的精度丢失。
        // 所以我们断言一个合理的区间，而不是绝对等于 1_000_000
        val maxVal = histogram.maxValue
        assertTrue(maxVal >= limit, "最大值必须大于或等于输入的最大值")

        val p50 = histogram.valueAtPercentile(50.0)
        val p99 = histogram.valueAtPercentile(99.0)

        assertTrue(p50 in 490_000L..510_000L, "中位数应在 50万 附近")
        assertTrue(p99 in 980_000L..1_000_000L, "99分位数应接近 99万")
        assertTrue(p99 > p50, "99分位数必然大于50分位数")
    }

    @Test
    fun testTypicalLatencySimulation() {
        // 模拟网络请求延迟（单位：毫秒）
        val histogram = Histogram(1L, 60_000L, 3)

        // 模拟 9900 次正常的快速请求 (10ms - 50ms)
        for (i in 1..9900) {
            val fastLatency = (10L..50L).random()
            histogram.recordValue(fastLatency)
        }

        // 模拟 100 次极慢的超时请求 (1000ms - 5000ms) - 长尾效应
        for (i in 1..100) {
            val slowLatency = (1000L..5000L).random()
            histogram.recordValue(slowLatency)
        }

        assertEquals(10000L, histogram.totalCount)

        // 核心验证：99% 的请求应该在 50ms 左右，而 99.9% 应该直接飙升到 1000ms 以上
        val p99 = histogram.valueAtPercentile(99.0)
        val p999 = histogram.valueAtPercentile(99.9)

        assertTrue(p99 <= 60L, "99%的请求应该不受极少数长尾影响，依然很快 (<= 60ms)")
        assertTrue(p999 >= 1000L, "99.9%分位数必须能捕捉到长尾的高延迟")
    }

    @Test
    fun testRecordWithCount() {
        val histogram = Histogram(1L, 1_000_000L, 3)

        // 一次性批量记录 10,000 个值为 200 的数据
        histogram.recordValue(200L, 10_000L)
        histogram.recordValue(300L, 5_000L)

        assertEquals(15_000L, histogram.totalCount)
        assertEquals(200L, histogram.valueAtPercentile(10.0))
        assertEquals(200L, histogram.valueAtPercentile(50.0))
        // 2/3 的数据是 200，1/3 的数据是 300，所以 70% 的位置应该切到了 300
        assertTrue(histogram.valueAtPercentile(70.0) >= 300L)
    }

    @Test
    fun testReset() {
        val histogram = Histogram(1L, 1_000_000L, 3)
        histogram.recordValue(100)
        histogram.recordValue(200)

        assertEquals(2L, histogram.totalCount)

        // 测试重置功能
        histogram.reset()

        assertEquals(0L, histogram.totalCount)
        assertEquals(0L, histogram.maxValue)
        assertEquals(0L, histogram.minValue)
        assertEquals(0.0, histogram.mean)
    }

    @Test
    fun testInvalidInputsThrowExceptions() {
        val histogram = Histogram(1L, 1_000_000L, 3)

        // 测试负数输入 (Histogram 不支持负数)
        assertFailsWith<IllegalArgumentException>("记录负数应该抛出异常") {
            histogram.recordValue(-10L)
        }

        // 测试超出最大追踪值的输入
        assertFailsWith<IllegalArgumentException>("超出 highestTrackableValue 应该抛出异常") {
            histogram.recordValue(2_000_000L)
        }

        // 测试不合理的百分位查询
        assertFailsWith<IllegalArgumentException>("百分位超出 0..100 范围应该抛出异常") {
            histogram.valueAtPercentile(100.1)
        }
    }
}