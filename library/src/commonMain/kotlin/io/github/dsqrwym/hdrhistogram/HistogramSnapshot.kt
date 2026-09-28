package io.github.dsqrwym.hdrhistogram

/**
 * A read-only snapshot interface for querying histogram statistics.
 *
 * This interface defines the set of query operations available on a histogram,
 * including total count, min/max values, mean, percentile queries, and count-at-value lookups.
 * It serves as the contract for read-only access to histogram data, and is commonly returned
 * by [Recorder.getIntervalHistogram] for safe, non-blocking statistical analysis.
 *
 * 直方图的只读快照查询接口。
 *
 * 该接口定义了对直方图可执行的所有查询操作，包括总计数、最小/最大值、均值、百分位查询和指定值的计数查找。
 * 它作为直方图数据只读访问的契约，常由 [Recorder.getIntervalHistogram] 返回，用于安全的、非阻塞的统计分析。
 *
 * @see Histogram
 * @see AtomicHistogram
 * @see Recorder
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
     * When the given percentile is > 0.0, the value returned will be the highest value that is
     * equivalent to (within the histogram's value resolution of) the value at which the cumulative
     * count of recorded values reaches (or exceeds) the given percentile of the total count.
     *
     * When the given percentile is 0.0, the value returned will be the lowest equivalent value
     * of the first non-zero count found in the histogram.
     *
     * 获取给定百分位处的值。
     *
     * 当给定百分位 > 0.0 时，返回的值为累积计数达到（或超过）总计数的给定百分比时的最高等价值
     * （在直方图的值分辨率范围内）。
     *
     * 当给定百分位为 0.0 时，返回直方图中找到的第一个非零计数的最低等价值。
     *
     * @param percentile The percentile for which to return the associated value, in range [0.0, 100.0].
     *                   要返回关联值的百分位，取值范围 [0.0, 100.0]。
     * @return The highest value that is equivalent to the value at the given percentile.
     *         给定百分位处的最高等价值。
     * @throws IllegalArgumentException if [percentile] is not in [0.0, 100.0].
     *                                  如果 [percentile] 不在 [0.0, 100.0] 范围内，则抛出异常。
     */
    fun valueAtPercentile(percentile: Double): Long
}