package io.github.dsqrwym.hdrhistogram

/**
 * A High Dynamic Range (HDR) Histogram.
 *
 * [Histogram] supports the recording and analyzing of sampled data value counts across a configurable
 * integer value range with configurable value precision within the range. Value precision is expressed
 * as the number of significant digits in the value recording, and provides control over value
 * quantization behavior across the value range and the subsequent value resolution at any given level.
 *
 * For example, a Histogram could be configured to track the counts of observed integer values between
 * 0 and 3,600,000,000 while maintaining a value precision of 3 significant digits across that range.
 * Value quantization within the range will thus be no larger than 1/1,000th (or 0.1%) of any value.
 * This example Histogram could be used to track and analyze the counts of observed response times
 * ranging between 1 microsecond and 1 hour in magnitude, while maintaining a value resolution of
 * 1 microsecond up to 1 millisecond, a resolution of 1 millisecond (or better) up to one second,
 * and a resolution of 1 second (or better) up to 1,000 seconds.
 *
 * [Histogram] is **not thread-safe**. For concurrent recording scenarios, use [AtomicHistogram]
 * or [Recorder] instead.
 *
 * **Usage example / 使用示例:**
 * ```kotlin
 * // Create a histogram covering values from 1 to 3,600,000,000 with 3 significant digits:
 * // 创建一个覆盖 1 到 3,600,000,000 范围、3 位有效数字精度的直方图：
 * val histogram = Histogram(
 *     lowestDiscernibleValue = 1L,
 *     highestTrackableValue = 3_600_000_000L,
 *     numberOfSignificantValueDigits = 3
 * )
 *
 * // Record some values / 记录一些值：
 * histogram.recordValue(1000L)
 * histogram.recordValue(5000L)
 * histogram.recordValue(100_000L, count = 5L)  // Record 5 occurrences of 100,000
 *
 * // Query statistics / 查询统计数据：
 * println(histogram.valueAtPercentile(50.0))  // Median / 中位数
 * println(histogram.valueAtPercentile(99.0))  // 99th percentile / 第99百分位
 * println(histogram.mean)                     // Mean / 均值
 * println(histogram.stdDeviation)             // Standard deviation / 标准差
 * println(histogram.maxValue)                 // Max value / 最大值
 * ```
 *
 * 高动态范围 (HDR) 直方图。
 *
 * [Histogram] 支持在可配置的整数值范围内，以可配置的值精度记录和分析采样数据值计数。
 * 值精度以记录值的有效数字位数表示，并控制整个值范围内的值量化行为以及任意级别的后续值分辨率。
 *
 * 例如，可以将 Histogram 配置为追踪 0 到 3,600,000,000 之间的观测整数值计数，
 * 同时在该范围内保持 3 位有效数字的精度。因此，范围内的值量化将不超过任何值的 1/1,000（即 0.1%）。
 *
 * [Histogram] **非线程安全**。对于并发记录场景，请改用 [AtomicHistogram] 或 [Recorder]。
 *
 * @param lowestDiscernibleValue The lowest value that can be discerned (distinguished from 0) by the histogram.
 *     Must be a positive integer >= 1. Any value smaller than this will be recorded as 0.
 *     直方图可辨识的最低值（与 0 可区分的最小值）。必须为 >= 1 的正整数。小于此值的数据将被记为 0。
 * @param highestTrackableValue The highest value to be tracked by the histogram.
 *     Must be >= (2 * lowestDiscernibleValue).
 *     直方图可追踪的最高值。必须 >= (2 * lowestDiscernibleValue)。
 * @param numberOfSignificantValueDigits Specifies the precision to use. This is the number of significant
 *     decimal digits to which the histogram will maintain value resolution and separation.
 *     Must be a non-negative integer between 0 and 5.
 *     指定使用的精度。这是直方图将保持值分辨率和分离度的有效十进制位数。
 *     必须为 0 到 5 之间的非负整数。
 *
 * @see AtomicHistogram
 * @see Recorder
 * @see HistogramSnapshot
 */
class Histogram(
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
    private val counts = LongArray(countsArrayLength)

    /**
     * 追踪目前为止记录到的最大数值
     */
    private var maxValueInternal = 0L

    /**
     * 追踪目前为止记录到的最小的非零数值（初始设为 Long.MAX_VALUE 作为哨兵值）
     */
    private var minNonZeroValueInternal = Long.MAX_VALUE

    override var totalCount: Long = 0L
        private set

    override val minValue: Long
        get() = when {
            totalCount == 0L -> 0L
            // 若 counts[0] > 0，说明存在 0 或低于最小分辨率的数值，直接返回 0
            counts[0] > 0L -> 0L
            // 否则，将记录到的最小正数 minNonZeroValueInternal 对齐到其区间内的最低值
            else -> lowestEquivalentValue(minNonZeroValueInternal)
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
        get() = if (minNonZeroValueInternal == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            lowestEquivalentValue(minNonZeroValueInternal)
        }

    override val maxValue: Long
        get() = if (totalCount == 0L) 0L else highestEquivalentValue(maxValueInternal)

    override val mean: Double
        get() = computeMean(totalCount, counts.size) {
            counts[it]
        }

    override val stdDeviation: Double
        get() = computeStdDeviation(totalCount, counts.size) {
            counts[it]
        }

    /**
     * Get the count of recorded values equivalent to [value] within the histogram's resolution.
     *
     * 获取与 [value] 在直方图分辨率范围内等价的所有已记录值的计数。
     *
     * @param value The value to look up. Must be within `[0, highestTrackableValue]`.
     *              要查询的值。必须在 `[0, highestTrackableValue]` 范围内。
     * @return The count recorded for [value].
     *         为 [value] 记录的计数。
     * @throws IllegalArgumentException if [value] is out of range. Note that the official Java
     *         implementation clamps the index instead of throwing.
     *         如果 [value] 超出范围则抛出异常。注意：官方 Java 实现是对下标做 clamp 而非抛异常。
     */
    fun countAtValue(value: Long): Long {
        requireRecordable(value)
        return counts[countsArrayIndex(value)]
    }

    override fun countAtIndex(index: Int): Long = counts[index]

    /**
     * Record a value in the histogram.
     *
     * Records the given [value] with the specified [count] (number of occurrences).
     * The value must be non-negative and must not exceed the histogram's highest trackable value.
     *
     * 在直方图中记录一个值。
     *
     * 以指定的 [count]（出现次数）记录给定的 [value]。
     * 该值必须为非负数，且不得超过直方图的最高可追踪值。
     *
     * @param value The value to be recorded. Must be >= 0 and <= highestTrackableValue.
     *              要记录的值。必须 >= 0 且 <= highestTrackableValue。
     * @param count The number of occurrences of this value to record. Must be > 0. Defaults to 1.
     *              要记录的该值出现次数。必须 > 0。默认为 1。
     * @throws IllegalArgumentException if [value] is negative, exceeds highestTrackableValue,
     *         or if [count] is not positive.
     *         如果 [value] 为负数、超过 highestTrackableValue 或 [count] 非正数，则抛出异常。
     */
    fun recordValue(value: Long, count: Long = 1L) {
        requireRecordable(value)
        require(count > 0L)

        val index = countsArrayIndex(value)
        counts[index] += count
        totalCount += count

        if (value > maxValueInternal) maxValueInternal = value
        // 先比大小（快速过滤掉绝大多数比 min 大的正常数值）
        if (value < minNonZeroValueInternal && value != 0L) {
            minNonZeroValueInternal = value
        }
    }

    /**
     * Record a value in this histogram, correcting for coordinated omission.
     *
     * When [value] is larger than [expectedIntervalBetweenValueSamples], the histogram
     * auto-generates an additional series of decreasingly-smaller value records, down to (but
     * not below) one sample interval. This compensates for the samples that were never taken
     * because the measuring thread itself was blocked, which would otherwise make tail
     * percentiles look far better than they really are.
     *
     * 在直方图中记录一个值，并校正协调遗漏。
     *
     * 当 [value] 大于 [expectedIntervalBetweenValueSamples] 时，自动补记一串递减的值，最小补到一个采样间隔为止。
     * 这补偿了由于测量线程被阻塞而样本没有被记录所造成的损失，否则尾部分位数会显著优于真实情况。
     *
     * @param value The value to be recorded. Must be >= 0 and <= highestTrackableValue.
     *              要记录的值。必须 >= 0 且 <= highestTrackableValue。
     * @param expectedIntervalBetweenValueSamples The expected interval between samples.
     *        Values <= 0 disable the correction, so only [value] is recorded.
     *        两次采样之间的期望间隔。<= 0 时不做校正，只记录 [value]。
     * @throws IllegalArgumentException if [value] is out of the recordable range.
     *                                  如果 [value] 超出可记录范围，则抛出异常。
     */
    fun recordValueWithExpectedInterval(value: Long, expectedIntervalBetweenValueSamples: Long) {
        recordValue(value)
        forEachCorrectedValue(value, expectedIntervalBetweenValueSamples) {
            recordValue(it)
        }
    }

    override fun valueAtPercentile(percentile: Double): Long {
        return computePercentile(percentile, totalCount, counts.size) {
            counts[it]
        }
    }

    /**
     * Add the contents of another histogram into this histogram.
     *
     * The source is merged **by value**: each non-zero slot is translated back to the value it
     * represents and re-recorded under this histogram's own layout. When both histograms share
     * the same layout (`bucketCount`, `subBucketCount`, `unitMagnitude`), counts are summed slot
     * by slot instead, which is an order of magnitude faster and produces the same result.
     *
     * The source must be quiescent for the duration of the call.
     *
     * 将另一个直方图的内容合并到此直方图中。
     *
     * 按**值**合并：源中每个非零槽都会反解成它代表的数值，再按本直方图的布局重新记录。
     * 若两者布局完全一致（`bucketCount`、`subBucketCount`、`unitMagnitude`），则改为逐槽
     * 累加——快一个数量级，且结果完全相同。
     *
     * 调用期间源直方图必须已静止（不再被写入）。
     *
     * @param other The histogram to merge; must be an instance created by this library
     *        ([Histogram] or [AtomicHistogram]).
     *        要合并的直方图；必须是本库创建的实例（[Histogram] 或 [AtomicHistogram]）。
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
            // 布局一致，直接累加
            // 实测：加 `if (otherCount > 0)` 跳零槽反而慢 29%~53%（破坏了 JIT 的向量化），故无条件写
            for (i in 0 until countsArrayLength) {
                counts[i] += other.countAtIndex(i)
            }
            totalCount += other.totalCount
            if (other.maxValue > maxValueInternal) maxValueInternal = other.maxValue
            val otherMin = other.minNonZeroValue
            if (otherMin < minNonZeroValueInternal) minNonZeroValueInternal = otherMin

            return
        }
        // 布局不一致 逐个添加
        val otherMaxIndex = other.countsArrayIndex(other.maxValue)
        for (i in 0..otherMaxIndex) {
            val otherCount = other.countAtIndex(i)
            if (otherCount > 0) {
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
        counts[index] += count
        totalCount += count
        if (value > maxValueInternal) maxValueInternal = value
        if (value != 0L && value < minNonZeroValueInternal) minNonZeroValueInternal = value
    }

    /**
     * Reset the histogram to its initial (empty) state.
     *
     * Clears all recorded counts and resets all statistics (totalCount, min, max) to their
     * initial values. The histogram's structure (value range and precision) remains unchanged.
     *
     * 将直方图重置为初始（空）状态。
     *
     * 清除所有已记录的计数并将所有统计数据（totalCount、最小值、最大值）重置为初始值。
     * 直方图的结构（值范围和精度）保持不变。
     */
    fun reset() {
        counts.fill(0L)
        totalCount = 0L
        maxValueInternal = 0L
        minNonZeroValueInternal = Long.MAX_VALUE
    }
}