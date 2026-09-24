package io.github.dsqrwym.hdrhistogram

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicLongArray
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class AtomicHistogram(
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

    /**
     * 总记录采样次数
     */
    val totalCount: Long
        get() = totalCountAtomic.load()

    val minValue: Long
        get() = when {
            totalCount == 0L -> 0L
            // 若 counts[0] > 0，说明存在 0 或低于最小分辨率的数值，直接返回 0
            counts.loadAt(0) > 0L -> 0L
            // 否则，将记录到的最小正数 minNonZeroValueInternal 对齐到其区间内的最低值
            else -> lowestEquivalentValue(minNonZeroValueInternal.load())
        }

    val maxValue: Long
        get() = if (totalCount == 0L) 0L else highestEquivalentValue(maxValueInternal.load())

    val mean: Double
        get() = computeMean(totalCount, counts.size) {
            counts.loadAt(it)
        }

    fun countAtValue(value: Long): Long {
        requireRecordable(value)
        return counts.loadAt(countsArrayIndex(value))
    }

    /**
     * 多线程无锁并发写入，利用 CPU 的硬件原子总线指令（XADD / LDADD），实现极端无锁并发吞吐
     *
     * @param value 要录入的数值 (非负)
     * @param count 该数值出现的频次，默认为 1L
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

    /**
     * 查询满足指定百分位数 [percentile] 的采样测量值。
     */
    fun valueAtPercentile(percentile: Double): Long {
        return computePercentile(percentile, totalCount, counts.size) {
            counts.loadAt(it)
        }
    }

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