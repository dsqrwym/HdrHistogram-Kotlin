package io.github.dsqrwym.hdrhistogram

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicLongArray
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A thread-safe, lock-free High Dynamic Range (HDR) Histogram using atomic operations.
 *
 * [AtomicHistogram] provides the same recording and querying functionality as [Histogram],
 * but uses atomic operations (CAS and atomic increment) for all count updates, making it safe
 * for concurrent use by multiple writer threads without any locks.
 *
 * This is the internal concurrent histogram used by [Recorder] on platforms that support
 * multi-threading (JVM, Native). For most use cases, prefer using [Recorder] which provides
 * a higher-level API with double-buffered interval histogram collection.
 *
 * **Performance characteristics / 性能特征:**
 * - Write operations use hardware atomic instructions (e.g., XADD/LDADD on x86/ARM),
 *   providing wait-free recording throughput.
 * - Min/max value tracking uses CAS (Compare-And-Swap) loops with optimistic fast-path checks.
 *
 * 线程安全的、无锁的高动态范围 (HDR) 直方图，使用原子操作。
 *
 * [AtomicHistogram] 提供与 [Histogram] 相同的记录和查询功能，
 * 但对所有计数更新使用原子操作（CAS 和原子递增），使其无需任何锁即可安全地被多个写入线程并发使用。
 *
 * 这是 [Recorder] 在支持多线程的平台（JVM、Native）上使用的内部并发直方图。
 * 对于大多数用例，建议使用 [Recorder]，它提供了带有双缓冲区间直方图收集的更高级 API。
 *
 * @param lowestDiscernibleValue The lowest value that can be discerned (distinguished from 0) by the histogram.
 *     Must be a positive integer >= 1.
 *     直方图可辨识的最低值（与 0 可区分的最小值）。必须为 >= 1 的正整数。
 * @param highestTrackableValue The highest value to be tracked by the histogram.
 *     Must be >= (2 * lowestDiscernibleValue).
 *     直方图可追踪的最高值。必须 >= (2 * lowestDiscernibleValue)。
 * @param numberOfSignificantValueDigits Specifies the precision to use (0-5).
 *     指定使用的精度（0-5）。
 *
 * @see Histogram
 * @see Recorder
 */
@OptIn(ExperimentalAtomicApi::class)
class AtomicHistogram(
    lowestDiscernibleValue: Long = 1_000,
    highestTrackableValue: Long,
    numberOfSignificantValueDigits: Int = 3
) : HistogramCoreAlg(
    lowestDiscernibleValue,
    highestTrackableValue,
    numberOfSignificantValueDigits
), HistogramSnapshot {

    /**
     * 底层物理存储数组：每个位置存储对应数值出现的频次 (Hits)
     */
    private val counts = AtomicLongArray(countsArrayLength)

    /**
     * 内部总记录采样次数
     */
    private val totalCountAtomic = AtomicLong(0L)

    /**
     * 追踪目前为止记录到的最大数值
     */
    private val maxValueInternal = AtomicLong(0L)

    /**
     * 追踪目前为止记录到的最小的非零数值（初始设为 Long.MAX_VALUE 作为哨兵值）
     */
    private val minNonZeroValueInternal = AtomicLong(Long.MAX_VALUE)

    override val totalCount: Long
        get() = totalCountAtomic.load()

    override val minValue: Long
        get() = when {
            totalCount == 0L -> 0L
            // 若 counts[0] > 0，说明存在 0 或低于最小分辨率的数值，直接返回 0
            counts.loadAt(0) > 0L -> 0L
            // 否则，将记录到的最小正数 minNonZeroValueInternal 对齐到其区间内的最低值
            else -> lowestEquivalentValue(minNonZeroValueInternal.load())
        }

    override val maxValue: Long
        get() = if (totalCount == 0L) 0L else highestEquivalentValue(maxValueInternal.load())

    override val mean: Double
        get() = computeMean(totalCount, counts.size) {
            counts.loadAt(it)
        }

    fun countAtValue(value: Long): Long {
        requireRecordable(value)
        return counts.loadAt(countsArrayIndex(value))
    }

    override fun countAtIndex(index: Int): Long = counts.loadAt(index)

    /**
     * Record a value in the histogram using lock-free atomic operations.
     *
     * This method is safe to call concurrently from multiple threads. It uses hardware
     * atomic increment instructions for maximum throughput.
     *
     * 使用无锁原子操作在直方图中记录一个值。
     *
     * 此方法可安全地从多个线程并发调用。它使用硬件原子递增指令以获得最大吞吐量。
     *
     * @param value The value to be recorded. Must be >= 0 and <= highestTrackableValue.
     *              要记录的值。必须 >= 0 且 <= highestTrackableValue。
     * @param count The number of occurrences to record. Must be > 0. Defaults to 1.
     *              要记录的出现次数。必须 > 0。默认为 1。
     * @throws IllegalArgumentException if [value] or [count] is out of valid range.
     *         如果 [value] 或 [count] 超出有效范围，则抛出异常。
     */
    fun recordValue(value: Long, count: Long = 1L) {
        requireRecordable(value)
        require(count > 0L)

        val index = countsArrayIndex(value)
        counts.addAndFetchAt(index, count)
        totalCountAtomic.addAndFetch(count)

        // 乐观判断跳过大部分请求
        if (value > maxValueInternal.load()) {
            updateMaxValue(value)
        }
        // 乐观判断跳过大部分请求
        if (value < minNonZeroValueInternal.load() && value != 0L) {
            updateMinNonZeroValue(value)
        }
    }

    /**
     * CAS 循环原子更新极值
     */
    private fun updateMaxValue(value: Long) {
        while (true) {
            val current = maxValueInternal.load()
            // 如果当前值小于等于历史最大值，直接跳出。避免 CAS 总线竞争
            if (value <= current) break
            // 只有当内存里的值依然等于刚才读到的 current 时，才允许改
            if (maxValueInternal.compareAndSet(current, value)) break
        }
    }

    private fun updateMinNonZeroValue(value: Long) {
        while (true) {
            val current = minNonZeroValueInternal.load()
            if (value >= current) break
            if (minNonZeroValueInternal.compareAndSet(current, value)) break
        }
    }

    override fun valueAtPercentile(percentile: Double): Long {
        return computePercentile(percentile, totalCount, counts.size) {
            counts.loadAt(it)
        }
    }

    /**
     * Reset the histogram to its initial (empty) state using atomic stores.
     *
     * 使用原子存储将直方图重置为初始（空）状态。
     */
    fun reset() {
        val length = counts.size
        for (i in 0 until length) {
            counts.storeAt(i, 0L)
        }
        totalCountAtomic.store(0L)
        maxValueInternal.store(0L)
        minNonZeroValueInternal.store(Long.MAX_VALUE)
    }
}