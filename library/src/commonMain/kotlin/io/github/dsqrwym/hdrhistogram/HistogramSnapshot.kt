package io.github.dsqrwym.hdrhistogram

/**
 * A read-only view of histogram statistics.
 *
 * This interface defines the query surface shared by [Histogram] and [AtomicHistogram]: total
 * count, min/max values, mean, standard deviation, percentile queries, and count lookups. It is
 * the contract to program against when a function only needs to read from a histogram.
 *
 * 直方图统计数据的只读视图。
 *
 * 该接口定义了 [Histogram] 与 [AtomicHistogram] 共享的查询面：总计数、最小/最大值、均值、
 * 标准差、百分位查询和计数查找。当函数只需要读取直方图时，应以本接口作为契约。
 *
 * @see Histogram
 * @see AtomicHistogram
 */
interface HistogramSnapshot {
    /**
     * The total number of recorded values in this histogram.
     *
     * 该直方图中已记录的值的总数。
     */
    val totalCount: Long

    /**
     * The lowest recorded value in this histogram.
     * Returns 0 if no values have been recorded, or if any values at or below
     * [HistogramCoreAlg.lowestDiscernibleValue] were recorded.
     *
     * 该直方图中已记录的最小值。
     * 如果尚未记录任何值，或者记录了小于等于最小可辨识值的数据，则返回 0。
     */
    val minValue: Long

    /**
     * The lowest recorded non-zero value, aligned down to the lowest equivalent value
     * within the histogram's resolution.
     * Returns [Long.MAX_VALUE] if no non-zero value has been recorded.
     *
     * 记录到的最低非零值，已向下对齐到其所在区间的最低等价值（与官方 getMinNonZeroValue 一致）。
     * 若尚未记录任何非零值，返回 [Long.MAX_VALUE]。
     */
    val minNonZeroValue: Long

    /**
     * The highest recorded value in this histogram, adjusted to the highest equivalent value
     * within the value's resolution range.
     *
     * 该直方图中已记录的最大值，已调整到该值分辨率区间内的最高等价值。
     */
    val maxValue: Long

    /**
     * The computed mean (average) of all recorded values in this histogram.
     * Returns 0.0 if no values have been recorded.
     *
     * 该直方图中所有已记录值的计算均值（平均值）。
     * 如果尚未记录任何值，则返回 0.0。
     */
    val mean: Double

    /**
     * The computed standard deviation of all recorded values in this histogram.
     * Returns 0.0 if no values have been recorded.
     *
     * 该直方图中所有已记录值的计算标准差 （总体标准差，除以 N 而非 N -1）。
     * 如果尚未记录任何值，则返回 0.0。
     */
    val stdDeviation: Double

    /**
     * Get the count of recorded values at the given internal index.
     *
     * 获取给定内部索引处的已记录计数。
     *
     * @param index The internal counts array index.
     *              内部计数数组的索引。
     * @return The count at the given index.
     *         给定索引处的计数。
     */
    fun countAtIndex(index: Int): Long

    /**
     * Get the value at a given percentile.
     *
     * Values outside `[0.0, 100.0]` are clamped to the nearest boundary instead of throwing:
     * a negative percentile behaves like the first recorded bucket
     * (`highestEquivalentValue`), `0.0` returns that bucket's `lowestEquivalentValue`, and a
     * percentile above 100 behaves like `100.0`.
     *
     * 获取给定百分位处的值。
     *
     * 超出 `[0.0, 100.0]` 的值会 clamp 到最近的边界，而不是抛异常：
     * 负数百分位等价于首个记录桶的 `highestEquivalentValue`，
     * `0.0` 返回该桶的 `lowestEquivalentValue`，大于 100 等价于 `100.0`。
     *
     * @param percentile The percentile for which to return the associated value.
     *                   Values outside `[0.0, 100.0]` are clamped.
     *                   要返回关联值的百分位。超出 `[0.0, 100.0]` 会被 clamp。
     * @return The highest equivalent value at the given percentile.
     *         给定百分位处的最高等价值。
     * @throws IllegalArgumentException if [percentile] is `Double.NaN`.
     *                                  如果 [percentile] 是 `Double.NaN` 则抛出异常。
     */
    fun valueAtPercentile(percentile: Double): Long
}