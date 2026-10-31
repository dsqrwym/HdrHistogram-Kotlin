package benchmark

import io.github.dsqrwym.hdrhistogram.Histogram
import kotlinx.benchmark.*
import kotlin.random.Random

/**
 * 本项目 (HdrHistogram-Kotlin) 吞吐量基准测试
 *
 * 评估在常规单一数值、变化数值（涵盖不同量级与子桶）、以及批量写入场景下的写入吞吐量 (ops/sec)。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class KtRecordThroughputBench {

    private lateinit var histogram: Histogram
    private val mask = 65535
    private val varyingValues = LongArray(65536)
    private var index = 0

    @Setup
    fun setup() {
        histogram = Histogram(1L, 10_000_000L, 3)
        val rnd = Random(42)
        // 模拟典型生产环境延迟分布：跨多个数量级（从微秒级到毫秒级长尾）
        for (i in 0 until 65536) {
            varyingValues[i] = when (i % 100) {
                in 0..89 -> rnd.nextLong(100L, 5_000L)       // 90% 常规低延迟 (100ns ~ 5us)
                in 90..98 -> rnd.nextLong(5_000L, 50_000L)   // 9% 中等延迟 (5us ~ 50us)
                else -> rnd.nextLong(50_000L, 5_000_000L)    // 1% 长尾毛刺 (50us ~ 5ms)
            }
        }
    }

    /** 最佳情况：命中相同桶（CPU 缓存命中率最高） */
    @Benchmark
    fun recordConstant() {
        histogram.recordValue(1000L)
    }

    /** 现实综合场景：跨桶寻址与无分支预测 */
    @Benchmark
    fun recordVarying() {
        val v = varyingValues[index and mask]
        index++
        histogram.recordValue(v)
    }

    /** 批量计数写入 */
    @Benchmark
    fun recordVaryingWithCount() {
        val v = varyingValues[index and mask]
        index++
        histogram.recordValue(v, 10L)
    }
}

/**
 * 本项目 (HdrHistogram-Kotlin) 写入延迟基准测试（纳秒级平均耗时）
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
open class KtRecordLatencyBench {

    private lateinit var histogram: Histogram
    private val mask = 65535
    private val varyingValues = LongArray(65536)
    private var index = 0

    @Setup
    fun setup() {
        histogram = Histogram(1L, 10_000_000L, 3)
        val rnd = Random(42)
        for (i in 0 until 65536) {
            varyingValues[i] = when (i % 100) {
                in 0..89 -> rnd.nextLong(100L, 5_000L)
                in 90..98 -> rnd.nextLong(5_000L, 50_000L)
                else -> rnd.nextLong(50_000L, 5_000_000L)
            }
        }
    }

    @Benchmark
    fun recordConstantLatency() {
        histogram.recordValue(1000L)
    }

    @Benchmark
    fun recordVaryingLatency() {
        val v = varyingValues[index and mask]
        index++
        histogram.recordValue(v)
    }

    @Benchmark
    fun recordValueLatency() {
        val v = varyingValues[index and mask]
        index++
        histogram.recordValue(v, 10L)
    }
}

/**
 * 本项目 (HdrHistogram-Kotlin) 查询分析基准测试
 *
 * 在预先填充好 10 万个真实延迟样本的 Histogram 上，测试统计计算与分位数查询的耗时。
 * 注意：必须返回计算结果（return Long / Double），防止 JIT 编译器进行死代码消除。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
open class KtQueryBench {

    private lateinit var histogram: Histogram

    @Setup
    fun setup() {
        histogram = Histogram(1L, 10_000_000L, 3)
        val rnd = Random(42)
        // 预填充 100,000 条真实样本
        for (i in 0 until 90_000) {
            histogram.recordValue(rnd.nextLong(100L, 5_000L))
        }
        for (i in 0 until 9_000) {
            histogram.recordValue(rnd.nextLong(5_000L, 50_000L))
        }
        for (i in 0 until 1_000) {
            histogram.recordValue(rnd.nextLong(50_000L, 5_000_000L))
        }
    }

    @Benchmark
    fun getP50(): Long = histogram.valueAtPercentile(50.0)

    @Benchmark
    fun getP90(): Long = histogram.valueAtPercentile(90.0)

    @Benchmark
    fun getP99(): Long = histogram.valueAtPercentile(99.0)

    @Benchmark
    fun getP999(): Long = histogram.valueAtPercentile(99.9)

    @Benchmark
    fun getMean(): Double = histogram.mean

    @Benchmark
    fun getMinValue(): Long = histogram.minValue

    @Benchmark
    fun getMaxValue(): Long = histogram.maxValue

    @Benchmark
    fun getTotalCount(): Long = histogram.totalCount
}

/**
 * 本项目 (HdrHistogram-Kotlin) 重置操作基准测试
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class KtResetBench {

    private lateinit var histogram: Histogram

    @Setup
    fun setup() {
        histogram = Histogram(1L, 10_000_000L, 3)
        for (i in 1..10_000) {
            histogram.recordValue(i.toLong())
        }
    }

    @Benchmark
    fun reset() {
        histogram.reset()
    }
}

/**
 * 官方 1:1 对标吞吐量基准测试（与 Gil Tene 的 HdrHistogramRecordingBench.java 对齐）
 *
 * 官方源码位置：
 * https://github.com/HdrHistogram/HdrHistogram/blob/master/HdrHistogram-benchmarks/src/main/java/bench/HdrHistogramRecordingBench.java
 *
 * 参数与逻辑完全一致：
 * - highestTrackableValue = 3600L * 1000 * 1000（1小时，以微秒为单位）
 * - numberOfSignificantValueDigits = 3
 * - testValueLevel = 12340L
 * - 核心写入：histogram.recordValue(testValueLevel + (i++ and 0x800))
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class KtOfficialAlignedThroughputBench {

    private val highestTrackableValue = 3600L * 1000 * 1000
    private val numberOfSignificantValueDigits = 3
    private val testValueLevel = 12340L

    private lateinit var histogram: Histogram
    private var i = 0

    @Setup
    fun setup() {
        histogram = Histogram(1L, highestTrackableValue, numberOfSignificantValueDigits)
    }

    /** 100% 对应官方 HdrHistogramRecordingBench.rawRecordingSpeed() */
    @Benchmark
    fun rawRecordingSpeed() {
        histogram.recordValue(testValueLevel + (i++ and 0x800))
    }
}

/**
 * 官方 1:1 对标写入延迟基准测试（纳秒级平均耗时）
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
open class KtOfficialAlignedLatencyBench {

    private val highestTrackableValue = 3600L * 1000 * 1000
    private val numberOfSignificantValueDigits = 3
    private val testValueLevel = 12340L

    private lateinit var histogram: Histogram
    private var i = 0

    @Setup
    fun setup() {
        histogram = Histogram(1L, highestTrackableValue, numberOfSignificantValueDigits)
    }

    @Benchmark
    fun rawRecordingLatency() {
        histogram.recordValue(testValueLevel + (i++ and 0x800))
    }
}

/**
 * 合并操作基准测试（同布局快速路径 / 异构布局逐值重录路径）
 *
 * 同布局走逐槽累加，异构布局必须把每个非零槽反解成数值后重新记录，耗时比同布局高约一个数量级。
 * 具体数值随机器与 JVM 版本变化，请以本机运行结果为准，用于监控 add 的性能回归。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class KtMergeBench {

    // 1 小时（纳秒），3 位精度 → countsArrayLength = 44032
    private val highest = 3_600_000_000_000_000L

    private lateinit var sameLayoutTarget: Histogram
    private lateinit var sameLayoutSource: Histogram
    private lateinit var wideTarget: Histogram
    private lateinit var narrowSource: Histogram

    @Setup
    fun setup() {
        sameLayoutTarget = Histogram(1L, highest, 3)
        sameLayoutSource = Histogram(1L, highest, 3)
        wideTarget = Histogram(1L, 100_000_000_000_000_000L, 3)
        narrowSource = Histogram(1L, highest, 3)

        val rnd = Random(42)
        repeat(2_000) {
            val value = rnd.nextLong(1L, highest)
            sameLayoutSource.recordValue(value)
            narrowSource.recordValue(value)
        }
    }

    /** 同布局：逐槽累加（Recorder 每区间合并的真实路径） */
    @Benchmark
    fun mergeSameLayout() {
        sameLayoutTarget.add(sameLayoutSource)
    }

    /** 异构布局：逐值重录 */
    @Benchmark
    fun mergeDifferentLayout() {
        wideTarget.add(narrowSource)
    }
}
