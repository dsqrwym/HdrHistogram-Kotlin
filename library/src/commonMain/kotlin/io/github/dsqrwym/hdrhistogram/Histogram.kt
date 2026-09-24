package io.github.dsqrwym.hdrhistogram

class Histogram(
    /**
     * 最小和分辨单位，以纳秒为基础。
     * 任何小于它的值都会被视为 0 .
     */
    lowestDiscernibleValue: Long = 1_000,
    /**
     * 最大输入值
     */
    highestTrackableValue: Long,
    /**
     * 精度等级，越大越精确
     * 0    -   100%   相对分辨率
     * 1    -   10%    相对分辨率
     * 2    -   1%     相对分辨率
     * 3    -   0.1%   相对分辨率
     * 4    -   0.01%  相对分辨率
     * 5    -   0.001% 相对分辨率
     */
    numberOfSignificantValueDigits: Int = 3
) : HistogramCoreAlg(
    lowestDiscernibleValue,
    highestTrackableValue,
    numberOfSignificantValueDigits
) {

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

    /**
     * 总记录采样次数
     */
    var totalCount: Long = 0L
        private set

    val minValue: Long
        get() = when {
            totalCount == 0L -> 0L
            // 若 counts[0] > 0，说明存在 0 或低于最小分辨率的数值，直接返回 0
            counts[0] > 0L -> 0L
            // 否则，将记录到的最小正数 minNonZeroValueInternal 对齐到其区间内的最低值
            else -> lowestEquivalentValue(minNonZeroValueInternal)
        }

    val maxValue: Long
        get() = if (totalCount == 0L) 0L else highestEquivalentValue(maxValueInternal)

    val mean: Double
        get() = computeMean(totalCount, counts.size) {
            counts[it]
        }

    fun countAtValue(value: Long): Long {
        requireRecordable(value)
        return counts[countsArrayIndex(value)]
    }

    /**
     * 记录数值及其出现的频次 (Hits)。
     *
     * @param value 要录入的数值 (非负)
     * @param count 该数值出现的频次，默认为 1L
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
     * 查询满足指定百分位数 [percentile] 的采样测量值。
     */
    fun valueAtPercentile(percentile: Double): Long {
        return computePercentile(percentile, totalCount, counts.size) {
            counts[it]
        }
    }

    fun reset() {
        counts.fill(0L)
        totalCount = 0L
        maxValueInternal = 0L
        minNonZeroValueInternal = Long.MAX_VALUE
    }
}