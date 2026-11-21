package test

import io.github.dsqrwym.hdrhistogram.Histogram
import io.github.dsqrwym.hdrhistogram.Recorder
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 严谨的 JVM 内存分配与物理占用测试
 *
 * 利用 JVM 的 ThreadMXBean 精确到单个字节探测在执行大量记录与重置操作时的堆内存分配量，
 * 验证直方图是否真正做到完全零 GC（Zero Allocation, 0 B/op）。
 */
class JvmMemoryAllocationTest {

    private val threadMXBean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /**
     * 测量指定代码块在当前线程中分配的堆内存字节数
     */
    private inline fun measureAllocatedBytes(block: () -> Unit): Long {
        val threadId = Thread.currentThread().id
        val before = threadMXBean.getThreadAllocatedBytes(threadId)
        block()
        val after = threadMXBean.getThreadAllocatedBytes(threadId)
        return after - before
    }

    /**
     * 验证 Histogram.recordValue 在执行 1,000,000 次操作期间的堆内存分配是否为 0 字节
     */
    @Test
    fun testHistogramZeroAllocationDuringRecording() {
        val histogram = Histogram(1L, 10_000_000L, 3)

        // 充分预热以确保 JIT 达到最高编译等级 (C2)，排除类加载与 JIT OSR 的临时分配
        for (i in 1..200_000) {
            histogram.recordValue(i.toLong())
        }
        histogram.reset()

        val operations = 1_000_000
        val allocatedBytes = measureAllocatedBytes {
            for (i in 1..operations) {
                histogram.recordValue((i % 5_000_000).toLong())
            }
        }

        val bytesPerOp = allocatedBytes.toDouble() / operations
        println("Kotlin Histogram - $operations recordValue calls: $allocatedBytes bytes allocated ($bytesPerOp B/op)")
        assertTrue(bytesPerOp < 0.001, "Histogram.recordValue must be allocation free (actual: $bytesPerOp B/op)")
    }

    /**
     * 验证官方 Java HdrHistogram 在执行 1,000,000 次操作期间的堆内存分配
     */
    @Test
    fun testOfficialJavaZeroAllocationDuringRecording() {
        val histogram = org.HdrHistogram.Histogram(1L, 10_000_000L, 3)

        for (i in 1..200_000) {
            histogram.recordValue(i.toLong())
        }
        histogram.reset()

        val operations = 1_000_000
        val allocatedBytes = measureAllocatedBytes {
            for (i in 1..operations) {
                histogram.recordValue((i % 5_000_000).toLong())
            }
        }

        val bytesPerOp = allocatedBytes.toDouble() / operations
        println("Official Java Histogram - $operations recordValue calls: $allocatedBytes bytes allocated ($bytesPerOp B/op)")
        assertTrue(bytesPerOp < 0.001, "official HdrHistogram is allocation free as well (actual: $bytesPerOp B/op)")
    }

    /**
     * 验证 Recorder 在双缓冲翻转与容器复用场景下的零分配特性
     */
    @Test
    fun testRecorderZeroAllocationDuringIntervalRecycling() {
        val recorder = Recorder(1L, 10_000_000L, 3)

        // 先执行一次初始化提取，让 recycle 容器分配出来
        var recycle = recorder.getIntervalHistogram(null)

        // 预热
        repeat((1..10_000).count()) {
            recorder.recordValue(100L)
            recycle = recorder.getIntervalHistogram(recycle)
        }

        val iterations = 50_000
        val allocatedBytes = measureAllocatedBytes {
            for (i in 1..iterations) {
                recorder.recordValue((i % 10_000).toLong())
                recycle = recorder.getIntervalHistogram(recycle)
            }
        }

        val bytesPerOp = allocatedBytes.toDouble() / iterations
        println("Kotlin Recorder - $iterations record + interval extraction (recycled) cycles: $allocatedBytes bytes allocated ($bytesPerOp B/op)")
        assertTrue(
            bytesPerOp < 0.001,
            "Recorder must be allocation free when recycling interval histograms (actual: $bytesPerOp B/op)"
        )
    }

    /**
     * 验证全量重置 reset() 过程中的零分配特性。
     *
     * 用双窗口差分抵消固定开销（JIT/OSR、ThreadMXBean 采样、首窗口类加载），
     * 与 AddOracleTest.mergeIsAllocationFree 同一手法；单窗口在 CI 上会因固定开销抖动。
     */
    @Test
    fun testResetZeroAllocation() {
        val histogram = Histogram(1L, 10_000_000L, 3)
        for (i in 1..10_000) {
            histogram.recordValue(i.toLong())
        }

        // 充分预热，避免把 JIT/OSR 的一次性分配算进测量窗口。
        repeat(10_000) {
            histogram.reset()
        }

        val smallResets = 10_000
        val largeResets = 100_000
        val smallBytes = measureAllocatedBytes {
            repeat(smallResets) {
                histogram.reset()
            }
        }
        val largeBytes = measureAllocatedBytes {
            repeat(largeResets) {
                histogram.reset()
            }
        }

        val bytesPerReset = (largeBytes - smallBytes).toDouble() / (largeResets - smallResets)
        println(
            "Kotlin Histogram - reset allocation: small=$smallBytes B, large=$largeBytes B " +
                    "-> $bytesPerReset B/op"
        )
        assertTrue(
            bytesPerReset < 0.1,
            "Histogram.reset() must not allocate (actual: $bytesPerReset B/op)"
        )
    }

    /**
     * 物理常驻内存推导与对比验证
     */
    @Test
    fun testPhysicalMemoryFootprintComparison() {
        // 规格 1: 1000 万微秒，精度 3
        val kt10M = Histogram(1L, 10_000_000L, 3)
        val java10M = org.HdrHistogram.Histogram(1L, 10_000_000L, 3)

        println("10M / 3 digits - Kotlin estimated footprint: ${kt10M.estimatedFootprintInBytes} bytes (~${kt10M.estimatedFootprintInBytes / 1024} KB)")
        println("10M / 3 digits - Official estimated footprint: ${java10M.estimatedFootprintInBytes} bytes (~${java10M.estimatedFootprintInBytes / 1024} KB)")
        // 二者物理底层数组大小完全相同，仅对象头估算差 448 字节 (Java 512B vs Kotlin 64B)
        assertEquals(java10M.estimatedFootprintInBytes.toLong() - 512L, kt10M.estimatedFootprintInBytes - 64L)

        // 规格 2: 1 小时 (36 亿微秒)，精度 3
        val kt1Hour = Histogram(1L, 3600L * 1000 * 1000, 3)
        val java1Hour = org.HdrHistogram.Histogram(1L, 3600L * 1000 * 1000, 3)

        println("1 hour / 3 digits - Kotlin estimated footprint: ${kt1Hour.estimatedFootprintInBytes} bytes (~${kt1Hour.estimatedFootprintInBytes / 1024} KB)")
        println("1 hour / 3 digits - Official estimated footprint: ${java1Hour.estimatedFootprintInBytes} bytes (~${java1Hour.estimatedFootprintInBytes / 1024} KB)")
        assertEquals(java1Hour.estimatedFootprintInBytes.toLong() - 512L, kt1Hour.estimatedFootprintInBytes - 64L)

        // 规格 3: 1 天 (864 亿微秒)，精度 3
        val kt1Day = Histogram(1L, 24L * 3600 * 1000 * 1000, 3)
        val java1Day = org.HdrHistogram.Histogram(1L, 24L * 3600 * 1000 * 1000, 3)

        println("1 day / 3 digits - Kotlin estimated footprint: ${kt1Day.estimatedFootprintInBytes} bytes (~${kt1Day.estimatedFootprintInBytes / 1024} KB)")
        println("1 day / 3 digits - Official estimated footprint: ${java1Day.estimatedFootprintInBytes} bytes (~${java1Day.estimatedFootprintInBytes / 1024} KB)")
        assertEquals(java1Day.estimatedFootprintInBytes.toLong() - 512L, kt1Day.estimatedFootprintInBytes - 64L)

        // 验证写入海量数据前后预估内存恒定
        val initialBytes = kt1Hour.estimatedFootprintInBytes
        for (i in 1..200_000) {
            kt1Hour.recordValue(i.toLong())
        }
        assertEquals(
            initialBytes,
            kt1Hour.estimatedFootprintInBytes,
            "footprint must stay constant after recording a large number of values"
        )
    }
}
