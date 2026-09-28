package io.github.dsqrwym.hdrhistogram

/**
 * A concurrent recorder that provides stable interval [Histogram] snapshots from live recorded
 * data without interrupting or stalling active recording of values.
 *
 * Each interval histogram provided contains all value counts accumulated since the previous
 * interval histogram was taken. This pattern is commonly used for logging interval histogram
 * information while recording is ongoing.
 *
 * [Recorder] supports concurrent [recordValue] calls. Recording calls are wait-free on
 * architectures that support atomic increment operations (e.g., JVM, Native), and use a simple
 * single-buffer approach on single-threaded platforms (JS, WasmJS).
 *
 * **Usage pattern / 使用模式:**
 * ```kotlin
 * val recorder = Recorder(highestTrackableValue = 3_600_000_000L)
 * var intervalHistogram: Histogram? = null
 *
 * // In recording threads / 在记录线程中：
 * recorder.recordValue(latencyValue)
 *
 * // Periodically in reporting thread / 在报告线程中定期调用：
 * intervalHistogram = recorder.getIntervalHistogram(intervalHistogram)
 * println("p99 = ${intervalHistogram.valueAtPercentile(99.0)}")
 * ```
 *
 * 一个支持并发的记录器，可在不中断或阻塞活跃值记录的情况下，提供稳定的区间 [Histogram] 快照。
 *
 * 每个提供的区间直方图包含自上次获取区间直方图以来累积的所有值计数。
 * 此模式通常用于在持续记录时记录区间直方图信息。
 *
 * [Recorder] 支持并发 [recordValue] 调用。在支持原子递增操作的架构上（如 JVM、Native），
 * 记录调用是无等待的；在单线程平台（JS、WasmJS）上使用简单的单缓冲方案。
 *
 * @param lowestDiscernibleValue The lowest value that can be discerned (distinguished from 0).
 *     Must be >= 1. Defaults to 1000 (1 microsecond when tracking nanoseconds).
 *     可辨识的最低值（与 0 可区分的最小值）。必须 >= 1。默认为 1000（追踪纳秒时即 1 微秒）。
 * @param highestTrackableValue The highest value to be tracked. Must be >= (2 * lowestDiscernibleValue).
 *     可追踪的最高值。必须 >= (2 * lowestDiscernibleValue)。
 * @param numberOfSignificantValueDigits Specifies the precision (0-5). Defaults to 3.
 *     指定精度（0-5）。默认为 3。
 *
 * @see Histogram
 * @see AtomicHistogram
 */
expect class Recorder(
    lowestDiscernibleValue: Long = 1_000,
    highestTrackableValue: Long,
    numberOfSignificantValueDigits: Int = 3
) {
    /**
     * Record a value in the recorder.
     *
     * This method is thread-safe on concurrent platforms (JVM, Native).
     *
     * 在记录器中记录一个值。
     *
     * 此方法在并发平台（JVM、Native）上是线程安全的。
     *
     * @param value The value to record. Must be >= 0 and <= highestTrackableValue.
     *              要记录的值。必须 >= 0 且 <= highestTrackableValue。
     * @param count The number of occurrences to record. Must be > 0. Defaults to 1.
     *              要记录的出现次数。必须 > 0。默认为 1。
     */
    fun recordValue(value: Long, count: Long = 1L)

    /**
     * Get a new interval histogram that contains all values recorded since the last call
     * to this method, and reset the recording for the next interval.
     *
     * The caller may optionally pass a previously returned [Histogram] via [histogramToRecycle]
     * to avoid repeated allocation. The recycled histogram will be reset and filled with the
     * new interval's data.
     *
     * 获取包含自上次调用此方法以来记录的所有值的新区间直方图，并为下一个区间重置记录。
     *
     * 调用者可选择通过 [histogramToRecycle] 传入之前返回的 [Histogram] 以避免重复分配。
     * 回收的直方图将被重置并填充新区间的数据。
     *
     * @param histogramToRecycle A previously returned histogram to recycle (optional).
     *                           要回收的先前返回的直方图（可选）。
     * @return A histogram containing the value counts accumulated since the last interval.
     *         包含自上次区间以来累积值计数的直方图。
     */
    fun getIntervalHistogram(histogramToRecycle: Histogram? = null): Histogram
}