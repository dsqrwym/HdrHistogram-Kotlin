package test

import io.github.dsqrwym.hdrhistogram.Histogram
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 与官方 Java 实现逐位对拍（容差 0）。
 *
 * 本实现与官方使用相同的代表值口径、遍历顺序与运算次序，
 * 因此 double 结果必须完全相等；任何差异都说明口径已经偏离。
 */
class StdDeviationOracleTest {

    private val lowest = 1L
    private val highest = 3_600_000_000L
    private val digits = 3

    private fun newKotlin() = Histogram(lowest, highest, digits)
    private fun newOfficial() = org.HdrHistogram.Histogram(lowest, highest, digits)

    /**
     * 官方 testGetStdDeviation 的 rawHistogram 数据集，逐位对拍
     */
    @Test
    fun testOfficialRawDataset() {
        val kt = newKotlin()
        val official = newOfficial()

        kt.recordValue(1000L, 10_000L)
        kt.recordValue(100_000_000L)
        official.recordValueWithCount(1000L, 10_000L)
        official.recordValue(100_000_000L)

        assertEquals(official.getTotalCount(), kt.totalCount)
        assertEquals(official.getMean(), kt.mean)
        assertEquals(official.getStdDeviation(), kt.stdDeviation)
    }

    /**
     * 随机数据对拍
     */
    @Test
    fun testRandomData() {
        val kt = newKotlin()
        val official = newOfficial()
        val random = Random(20261024)

        repeat(100_000) {
            val value = random.nextLong(0L, highest)
            kt.recordValue(value)
            official.recordValue(value)
        }

        assertEquals(official.getMean(), kt.mean)
        assertEquals(official.getStdDeviation(), kt.stdDeviation)
    }

    /**
     * 小值密集：全部落在桶宽为 1 的第 0 桶，
     * 专门验证“Double 中值”那 0.5 的偏差确实已消除
     */
    @Test
    fun testSmallValuesWhereBucketsAreUnitWide() {
        val kt = newKotlin()
        val official = newOfficial()
        val random = Random(42)

        repeat(50_000) {
            val value = random.nextLong(0L, 2_048L)
            kt.recordValue(value)
            official.recordValue(value)
        }

        assertEquals(official.getMean(), kt.mean)
        assertEquals(official.getStdDeviation(), kt.stdDeviation)
    }

    /**
     * 大计数值：验证 mean 内部走的是 Double 乘法，没有落进 Long 溢出
     */
    @Test
    fun testLargeCounts() {
        val kt = newKotlin()
        val official = newOfficial()
        val count = 1_000_000_000L

        kt.recordValue(3_000_000_000L, count)
        kt.recordValue(1_000L, count)
        official.recordValueWithCount(3_000_000_000L, count)
        official.recordValueWithCount(1_000L, count)

        assertEquals(official.getMean(), kt.mean)
        assertEquals(official.getStdDeviation(), kt.stdDeviation)
    }

    /**
     * 极端偏斜分布：低桶宽与高桶宽混合
     */
    @Test
    fun testSkewedDistribution() {
        val kt = newKotlin()
        val official = newOfficial()

        repeat(1_000) {
            kt.recordValue(1_000L + it)
            official.recordValue(1_000L + it)
        }
        repeat(10) {
            kt.recordValue(3_000_000_000L - it)
            official.recordValue(3_000_000_000L - it)
        }

        assertEquals(official.getMean(), kt.mean)
        assertEquals(official.getStdDeviation(), kt.stdDeviation)
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
     * 分配特征：inline 回调不应生成 Function 对象，标准差查询必须零分配。
     *
     * 采用两个连续测量窗口，只对第二个窗口断言：
     * 首个窗口可能包含一次性的 JIT / 类初始化开销，属于预热残留而非每次调用的分配。
     * 若确实存在每次调用的对象分配（例如 lambda 被装箱），两个窗口都会出现 16 B/op 以上的增量。
     */
    @Test
    fun testStdDeviationZeroAllocation() {
        val histogram = newKotlin()
        for (i in 1..100_000) {
            histogram.recordValue(i.toLong())
        }

        // 充分预热，确保 C2 编译完成
        repeat(20_000) { histogram.stdDeviation }

        val iterations = 10_000
        val firstWindow = measureAllocatedBytes {
            repeat(iterations) { histogram.stdDeviation }
        }
        val secondWindow = measureAllocatedBytes {
            repeat(iterations) { histogram.stdDeviation }
        }

        println("stdDeviation allocation -- window 1: $firstWindow B, window 2: $secondWindow B")
        println("stdDeviation steady-state allocation: ${secondWindow.toDouble() / iterations} B/op")
        assertEquals(0L, secondWindow, "stdDeviation must be allocation free in steady state (window 2: $secondWindow B)")
    }
}
