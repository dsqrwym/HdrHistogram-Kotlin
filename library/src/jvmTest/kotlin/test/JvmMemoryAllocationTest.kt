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

    /**
     * 测试专用的扩展属性：根据底层物理计数数组长度估算占用字节数
     */
    private val Histogram.estimatedFootprintInBytes: Long
        get() = (countsArrayLength.toLong() * 8L) + 64L

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
        println("【本项目 Histogram】执行 $operations 次 recordValue 分配的堆内存: $allocatedBytes 字节 (单次分配: $bytesPerOp B/op)")
        assertTrue(bytesPerOp < 0.001, "Histogram.recordValue 必须做到零对象分配 (实际单次分配: $bytesPerOp B/op)")
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
        println("【官方 Java 直方图】执行 $operations 次 recordValue 分配的堆内存: $allocatedBytes 字节 (单次分配: $bytesPerOp B/op)")
        assertTrue(bytesPerOp < 0.001, "官方 HdrHistogram 同样做到零对象分配 (实际单次分配: $bytesPerOp B/op)")
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
        for (i in 1..10_000) {
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
        println("【本项目 Recorder】执行 $iterations 次 记录+区间提取(复用) 分配的堆内存: $allocatedBytes 字节 (单次分配: $bytesPerOp B/op)")
        assertTrue(bytesPerOp < 0.001, "Recorder 复用容器提取区间直方图必须做到零分配 (实际单次分配: $bytesPerOp B/op)")
    }

    /**
     * 验证全量重置 reset() 过程中的零分配特性
     */
    @Test
    fun testResetZeroAllocation() {
        val histogram = Histogram(1L, 10_000_000L, 3)
        for (i in 1..10_000) {
            histogram.recordValue(i.toLong())
        }

        // 预热
        for (i in 1..1_000) {
            histogram.reset()
        }

        val resets = 100_000
        val allocatedBytes = measureAllocatedBytes {
            for (i in 1..resets) {
                histogram.reset()
            }
        }

        val bytesPerReset = allocatedBytes.toDouble() / resets
        println("【本项目 Histogram】执行 $resets 次 reset() 分配的堆内存: $allocatedBytes 字节 (单次分配: $bytesPerReset B/op)")
        assertTrue(bytesPerReset < 0.01, "Histogram.reset() 必须做到零堆分配 (实际单次分配: $bytesPerReset B/op)")
    }

    /**
     * 物理常驻内存推导与对比验证
     */
    @Test
    fun testPhysicalMemoryFootprintComparison() {
        // 规格 1: 1000 万微秒，精度 3
        val kt10M = Histogram(1L, 10_000_000L, 3)
        val java10M = org.HdrHistogram.Histogram(1L, 10_000_000L, 3)

        println("1000万/精度3 - 本项目预估占用: ${kt10M.estimatedFootprintInBytes} 字节 (~${kt10M.estimatedFootprintInBytes / 1024} KB)")
        println("1000万/精度3 - 官方预估占用:   ${java10M.estimatedFootprintInBytes} 字节 (~${java10M.estimatedFootprintInBytes / 1024} KB)")
        // 二者物理底层数组大小完全相同，仅对象头估算差 448 字节 (Java 512B vs Kotlin 64B)
        assertEquals(java10M.estimatedFootprintInBytes.toLong() - 512L, kt10M.estimatedFootprintInBytes - 64L)

        // 规格 2: 1 小时 (36 亿微秒)，精度 3
        val kt1Hour = Histogram(1L, 3600L * 1000 * 1000, 3)
        val java1Hour = org.HdrHistogram.Histogram(1L, 3600L * 1000 * 1000, 3)

        println("1小时/精度3  - 本项目预估占用: ${kt1Hour.estimatedFootprintInBytes} 字节 (~${kt1Hour.estimatedFootprintInBytes / 1024} KB)")
        println("1小时/精度3  - 官方预估占用:   ${java1Hour.estimatedFootprintInBytes} 字节 (~${java1Hour.estimatedFootprintInBytes / 1024} KB)")
        assertEquals(java1Hour.estimatedFootprintInBytes.toLong() - 512L, kt1Hour.estimatedFootprintInBytes - 64L)

        // 规格 3: 1 天 (864 亿微秒)，精度 3
        val kt1Day = Histogram(1L, 24L * 3600 * 1000 * 1000, 3)
        val java1Day = org.HdrHistogram.Histogram(1L, 24L * 3600 * 1000 * 1000, 3)

        println("1天/精度3    - 本项目预估占用: ${kt1Day.estimatedFootprintInBytes} 字节 (~${kt1Day.estimatedFootprintInBytes / 1024} KB)")
        println("1天/精度3    - 官方预估占用:   ${java1Day.estimatedFootprintInBytes} 字节 (~${java1Day.estimatedFootprintInBytes / 1024} KB)")
        assertEquals(java1Day.estimatedFootprintInBytes.toLong() - 512L, kt1Day.estimatedFootprintInBytes - 64L)

        // 验证写入海量数据前后预估内存恒定
        val initialBytes = kt1Hour.estimatedFootprintInBytes
        for (i in 1..200_000) {
            kt1Hour.recordValue(i.toLong())
        }
        assertEquals(initialBytes, kt1Hour.estimatedFootprintInBytes, "写入海量数据后物理内存占用恒定不变")
    }
}
