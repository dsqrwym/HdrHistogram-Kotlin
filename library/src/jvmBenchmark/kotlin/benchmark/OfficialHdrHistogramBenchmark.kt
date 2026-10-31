package benchmark

import kotlinx.benchmark.*
import kotlin.random.Random

/**
 * 官方 HdrHistogram Java 库吞吐量基准测试
 *
 * 与本项目的 KtRecordThroughputBench 使用完全相同的参数和数据分布，
 * 以便进行公平的性能对比。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class JavaRecordThroughputBench {

    private lateinit var histogram: org.HdrHistogram.Histogram
    private val mask = 65535
    private val varyingValues = LongArray(65536)
    private var index = 0

    @Setup
    fun setup() {
        histogram = org.HdrHistogram.Histogram(1L, 10_000_000L, 3)
        val rnd = Random(42)
        for (i in 0 until 65536) {
            varyingValues[i] = when (i % 100) {
                in 0..89 -> rnd.nextLong(100L, 5_000L)
                in 90..98 -> rnd.nextLong(5_000L, 50_000L)
                else -> rnd.nextLong(50_000L, 5_000_000L)
            }
        }
    }

    /** 最佳情况：命中相同桶 */
    @Benchmark
    fun recordConstant() {
        histogram.recordValue(1000L)
    }

    /** 现实综合场景：跨桶寻址 */
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
        histogram.recordValueWithCount(v, 10L)
    }
}

/**
 * 官方 HdrHistogram Java 库写入延迟基准测试
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
open class JavaRecordLatencyBench {

    private lateinit var histogram: org.HdrHistogram.Histogram
    private val mask = 65535
    private val varyingValues = LongArray(65536)
    private var index = 0

    @Setup
    fun setup() {
        histogram = org.HdrHistogram.Histogram(1L, 10_000_000L, 3)
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
        histogram.recordValueWithCount(v, 10L)
    }
}

/**
 * 官方 HdrHistogram Java 库查询分析基准测试
 *
 * 与本项目的 KtQueryBench 使用完全相同的数据填充和查询方式。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
open class JavaQueryBench {

    private lateinit var histogram: org.HdrHistogram.Histogram

    @Setup
    fun setup() {
        histogram = org.HdrHistogram.Histogram(1L, 10_000_000L, 3)
        val rnd = Random(42)
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
    fun getP50(): Long = histogram.getValueAtPercentile(50.0)

    @Benchmark
    fun getP90(): Long = histogram.getValueAtPercentile(90.0)

    @Benchmark
    fun getP99(): Long = histogram.getValueAtPercentile(99.0)

    @Benchmark
    fun getP999(): Long = histogram.getValueAtPercentile(99.9)

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
 * 官方 HdrHistogram Java 库重置操作基准测试
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class JavaResetBench {

    private lateinit var histogram: org.HdrHistogram.Histogram

    @Setup
    fun setup() {
        histogram = org.HdrHistogram.Histogram(1L, 10_000_000L, 3)
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
 * 官方 HdrHistogram Java 库 1:1 对标吞吐量基准测试
 *
 * 与本项目的 KtOfficialAlignedThroughputBench 参数完全一致。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class JavaOfficialAlignedThroughputBench {

    private val highestTrackableValue = 3600L * 1000 * 1000
    private val numberOfSignificantValueDigits = 3
    private val testValueLevel = 12340L

    private lateinit var histogram: org.HdrHistogram.Histogram
    private var i = 0

    @Setup
    fun setup() {
        histogram = org.HdrHistogram.Histogram(1L, highestTrackableValue, numberOfSignificantValueDigits)
    }

    /** 100% 对应官方 HdrHistogramRecordingBench.rawRecordingSpeed() */
    @Benchmark
    fun rawRecordingSpeed() {
        histogram.recordValue(testValueLevel + (i++ and 0x800))
    }
}

/**
 * 官方 HdrHistogram Java 库 1:1 对标写入延迟基准测试
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
open class JavaOfficialAlignedLatencyBench {

    private val highestTrackableValue = 3600L * 1000 * 1000
    private val numberOfSignificantValueDigits = 3
    private val testValueLevel = 12340L

    private lateinit var histogram: org.HdrHistogram.Histogram
    private var i = 0

    @Setup
    fun setup() {
        histogram = org.HdrHistogram.Histogram(1L, highestTrackableValue, numberOfSignificantValueDigits)
    }

    @Benchmark
    fun rawRecordingLatency() {
        histogram.recordValue(testValueLevel + (i++ and 0x800))
    }
}

/**
 * 官方 HdrHistogram Java 库合并操作基准测试
 *
 * 与本项目的 KtMergeBench 参数完全一致（1 小时纳秒量程、3 位精度、2000 个样本），
 * 分别覆盖同布局逐槽累加与异构逐值重录两条路径。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
open class JavaMergeBench {

    // 1 小时（纳秒），3 位精度 → countsArrayLength = 44032
    private val highest = 3_600_000_000_000_000L

    private lateinit var sameLayoutTarget: org.HdrHistogram.Histogram
    private lateinit var sameLayoutSource: org.HdrHistogram.Histogram
    private lateinit var wideTarget: org.HdrHistogram.Histogram
    private lateinit var narrowSource: org.HdrHistogram.Histogram

    @Setup
    fun setup() {
        sameLayoutTarget = org.HdrHistogram.Histogram(1L, highest, 3)
        sameLayoutSource = org.HdrHistogram.Histogram(1L, highest, 3)
        wideTarget = org.HdrHistogram.Histogram(1L, 100_000_000_000_000_000L, 3)
        narrowSource = org.HdrHistogram.Histogram(1L, highest, 3)

        val rnd = Random(42)
        repeat(2_000) {
            val value = rnd.nextLong(1L, highest)
            sameLayoutSource.recordValue(value)
            narrowSource.recordValue(value)
        }
    }

    /** 同布局：逐槽累加 */
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
