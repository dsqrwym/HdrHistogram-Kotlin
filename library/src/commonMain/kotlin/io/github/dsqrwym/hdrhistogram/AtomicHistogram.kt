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

    /**
     * The lowest recorded non-zero value, aligned down to the lowest equivalent value
     * within this histogram's resolution.
     * Returns [Long.MAX_VALUE] if no non-zero value has been recorded.
     *
     * 记录到的最低非零值，已向下对齐到其所在区间的最低等价值。
     * 若尚未记录任何非零值，返回 [Long.MAX_VALUE]。
     */
    override val minNonZeroValue: Long
        get() {
            val raw = minNonZeroValueInternal.load()
            return if (raw == Long.MAX_VALUE) Long.MAX_VALUE else lowestEquivalentValue(raw)
        }

    override val maxValue: Long
        get() = if (totalCount == 0L) 0L else highestEquivalentValue(maxValueInternal.load())

    override val mean: Double
        get() = computeMean(totalCount, counts.size) {
            counts.loadAt(it)
        }

    override val stdDeviation: Double
        get() = computeStdDeviation(totalCount, counts.size) {
            counts.loadAt(it)
        }

    /**
     * Get the count of recorded values equivalent to [value] within the histogram's resolution.
     *
     * Values in `[0, highestRecordableValue]` return their real count, including the
     * `(highestTrackableValue, highestRecordableValue]` gap that the counts array physically
     * covers. Values above `highestRecordableValue` return `0`; negative values throw.
     *
     * This intentionally differs from Java / JS / Rust index clamping (which reports the last
     * bucket's count for a value that was never recorded) and matches C's `0` result.
     *
     * 获取与 [value] 在直方图分辨率范围内等价的所有已记录值的计数。
     *
     * `[0, highestRecordableValue]` 内的值返回真实计数，包括
     * `(highestTrackableValue, highestRecordableValue]` 这段数组物理覆盖的区间。
     * 超过 `highestRecordableValue` 的值返回 `0`；负值抛出异常。
     *
     * 这是有意与 Java / JS / Rust 的“clamp 到末槽”不同（那会把从未记录过的值报成末槽计数），
     * 与 C 返回 `0` 的行为一致。
     *
     * @param value The value to look up. Must be >= 0.
     *              要查询的值。必须 >= 0。
     * @return The count recorded for [value], or `0` if [value] is above `highestRecordableValue`.
     *         为 [value] 记录的计数；若 [value] 超过 `highestRecordableValue` 则返回 `0`。
     * @throws IllegalArgumentException if [value] is negative.
     *                                  如果 [value] 为负数则抛出异常。
     */
    fun countAtValue(value: Long): Long {
        require(value >= 0L) { "value must be >= 0, was $value" }
        if (value > highestRecordableValue) return 0L
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
     * @param value The value to be recorded. Must be >= 0 and <= highestRecordableValue.
     *              要记录的值。必须 >= 0 且 <= highestRecordableValue。
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
     * Record a value in the histogram, correcting for coordinated omission.
     *
     * Lock-free equivalent of [Histogram.recordValueWithExpectedInterval]: the value and all
     * implied correction values are recorded with atomic increments, and the method is safe to
     * call concurrently from multiple threads.
     *
     * 直方图中记录一个值，并校正协调遗漏。
     *
     * 无锁等价于 [Histogram.recordValueWithExpectedInterval]：原值与所有补点值都通过原子自增写入，可安全地从多个线程并发调用。
     *
     * @param value The value to be recorded. Must be >= 0 and <= highestRecordableValue.
     *              要记录的值。必须 >= 0 且 <= highestRecordableValue。
     * @param expectedIntervalBetweenValueSamples The expected interval between samples.
     *        Values <= 0 disable the correction, so only [value] is recorded.
     *        两次采样之间的期望间隔。<= 0 时不做校正，只记录 [value]。
     */
    fun recordValueWithExpectedInterval(value: Long, expectedIntervalBetweenValueSamples: Long) {
        recordValue(value)
        forEachCorrectedValue(value, expectedIntervalBetweenValueSamples) {
            recordValue(it)
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
     * Merge the contents of another histogram into this one using atomic increments.
     *
     * Merge semantics are identical to [Histogram.add]: same-layout sources are summed slot by
     * slot, mismatched layouts are re-recorded by value. Each slot update is atomic, but the
     * merge as a whole is **not**: a concurrent [add] or [recordValue] may observe `totalCount`
     * and slot counts transiently inconsistent.
     *
     * The source must be quiescent for the duration of the call, otherwise torn reads may occur.
     *
     * 使用原子自增将另一个直方图的内容合并到此直方图中。
     *
     * 合并语义与 [Histogram.add] 完全一致：同布局逐槽累加，异构布局逐值重录。
     * 每个槽的更新是原子的，但**整次合并不是**：并发的 [add] 或 [recordValue] 可能观察到
     * `totalCount` 与槽内计数短暂不一致。
     *
     * 调用期间源直方图必须已静止，否则可能读到撕裂值。
     *
     * @param other The histogram to merge; must be an instance created by this library.
     *        要合并的直方图；必须是本库创建的实例。
     * @throws IllegalArgumentException if [other] contains values outside this histogram's
     *         recordable range (`other.maxValue > highestRecordableValue`). Note that the official
     *         Java implementation throws `ArrayIndexOutOfBoundsException` for the same condition.
     *         如果 [other] 包含超出本直方图可记录范围的值则抛出异常。注意：官方 Java 实现对
     *         同一条件抛出的是 `ArrayIndexOutOfBoundsException`。
     */
    fun add(other: HistogramCoreAlg) {
        require(highestRecordableValue >= other.maxValue) {
            "other histogram includes values that do not fit in this histogram's range"
        }

        if (hasSameLayoutAs(other)) {
            var observedOtherTotalCount = 0L
            for (i in 0 until countsArrayLength) {
                // 原子加是 RMW 操作，读-改-写， 单次代价高于一次分支判断。所以这里跳槽是净收益与 Histogram.add 相反。
                val otherCount = other.countAtIndex(i)
                if (otherCount > 0L) {
                    counts.addAndFetchAt(i, otherCount)
                    observedOtherTotalCount += otherCount
                }
            }
            totalCountAtomic.addAndFetch(observedOtherTotalCount)
            updateMaxValue(other.maxValue)
            val otherMin = other.minNonZeroValue
            if (otherMin != Long.MAX_VALUE) {
                updateMinNonZeroValue(otherMin)
            }
            return
        }

        val otherMaxIndex = other.countsArrayIndex(other.maxValue)
        for (i in 0..otherMaxIndex) {
            val otherCount = other.countAtIndex(i)
            if (otherCount > 0L) {
                recordCountUnchecked(other.valueFromIndex(i), otherCount)
            }
        }
    }

    /**
     * 合并专用写入：不做 highestTrackableValue 上界校验。
     *
     * 调用方必须先通过 [add] 的 highestRecordableValue 校验，保证 value 落在 counts 数组
     * 范围内（否则 countsArrayIndex 会越界）。
     */
    private fun recordCountUnchecked(value: Long, count: Long) {
        val index = countsArrayIndex(value)
        counts.addAndFetchAt(index, count)
        totalCountAtomic.addAndFetch(count)
        if (value > maxValueInternal.load()) updateMaxValue(value)
        if (value != 0L && value < minNonZeroValueInternal.load()) updateMinNonZeroValue(value)
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