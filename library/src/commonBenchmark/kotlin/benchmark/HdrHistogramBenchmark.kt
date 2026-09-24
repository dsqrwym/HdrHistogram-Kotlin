package benchmark

import io.github.dsqrwym.hdrhistogram.Histogram
import kotlinx.benchmark.*
import kotlin.random.Random

/**
 * 吞吐量基准测试：
 * 评估在常规单一数值、变化数值（涵盖不同量级与子桶）、以及批量写入场景下的写入吞吐量 (ops/sec)。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class HdrHistogramRecordBenchmark {

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

    /**
     * 最佳情况测试：命中相同桶（CPU L1 Cache Line 热点缓存命中率最高）
     */
    @Benchmark
    fun recordConstantValue() {
        histogram.recordValue(1000L)
    }

    /**
     * 现实综合测试：测试在不同数值跨度下的位运算寻址、跨桶寻址与无分支预测性能
     */
    @Benchmark
    fun recordVaryingValue() {
        val v = varyingValues[index and mask]
        index++
        histogram.recordValue(v)
    }

    /**
     * 批量计数写入吞吐量
     */
    @Benchmark
    fun recordVaryingValueWithCount() {
        val v = varyingValues[index and mask]
        index++
        histogram.recordValue(v, 10L)
    }
}

/**
 * 延迟基准测试：
 * 评估单次写入调用的纳秒级延迟 (ns/op)。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
open class HdrHistogramLatencyBenchmark {

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
 * 查询分析基准测试：
 * 在预先填充好 10 万个真实延迟样本的 Histogram 上，测试统计计算与分位数查询的耗时。
 * 注意：必须返回计算结果（return Long / Double），防止 JIT 编译器进行死代码消除 (Dead Code Elimination)。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
open class HdrHistogramQueryBenchmark {

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
    fun getValueAtPercentile50(): Long = histogram.valueAtPercentile(50.0)

    @Benchmark
    fun getValueAtPercentile90(): Long = histogram.valueAtPercentile(90.0)

    @Benchmark
    fun getValueAtPercentile99(): Long = histogram.valueAtPercentile(99.0)

    @Benchmark
    fun getValueAtPercentile999(): Long = histogram.valueAtPercentile(99.9)

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
 * 重置操作基准测试：
 * 评估重置 10 万条数据后的 Histogram（数组批量填零与元数据复位）的耗时。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class HdrHistogramResetBenchmark {

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
 * 官方 1:1 对标吞吐量基准测试 (Aligned with Gil Tene's HdrHistogramRecordingBench.java)
 *
 * 官方源码位置：
 * https://github.com/HdrHistogram/HdrHistogram/blob/master/HdrHistogram-benchmarks/src/main/java/bench/HdrHistogramRecordingBench.java
 *
 * 参数与逻辑完全一致：
 * - highestTrackableValue = 3600L * 1000 * 1000 (1小时，以微秒为单位 = 36亿)
 * - numberOfSignificantValueDigits = 3
 * - testValueLevel = 12340L
 * - 核心写入：histogram.recordValue(testValueLevel + (i++ and 0x800))
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class HdrHistogramOfficialBench {

    private val highestTrackableValue = 3600L * 1000 * 1000
    private val numberOfSignificantValueDigits = 3
    private val testValueLevel = 12340L

    private lateinit var histogram: Histogram
    private var i = 0

    @Setup
    fun setup() {
        histogram = Histogram(1L, highestTrackableValue, numberOfSignificantValueDigits)
    }

    /**
     * 100% 对应官方 HdrHistogramRecordingBench.rawRecordingSpeed()
     */
    @Benchmark
    fun rawRecordingSpeed() {
        histogram.recordValue(testValueLevel + (i++ and 0x800))
    }
}

/**
 * 官方 1:1 对标写入延迟基准测试 (纳秒级平均耗时)
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
open class HdrHistogramOfficialLatencyBench {

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

