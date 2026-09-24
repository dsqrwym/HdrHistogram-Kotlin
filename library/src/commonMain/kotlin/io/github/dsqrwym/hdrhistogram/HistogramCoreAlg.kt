@file:Suppress("NOTHING_TO_INLINE")

package io.github.dsqrwym.hdrhistogram

import kotlin.math.ceil

open class HistogramCoreAlg(
    /**
     * 最小和分辨单位，以纳秒为基础。
     * 任何小于它的值都会被视为 0 .
     */
    lowestDiscernibleValue: Long = 1_000,
    /**
     * 最大输入值
     */
    val highestTrackableValue: Long,
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
) {
    init {
        require(lowestDiscernibleValue >= 1) { "lowestDiscernibleValue must be positive" }
        // 后面要进行 ×2 等计算，所以不能让 Long 溢出
        require(lowestDiscernibleValue <= Long.MAX_VALUE / 2L)
        // 至少要达到最低分辨率尺度的两倍，否则没有意义
        require(highestTrackableValue > lowestDiscernibleValue * 2) { "highestTrackableValue must be bigger than twice of lowestDiscernibleValue" }
        require(numberOfSignificantValueDigits in 0..5) { "numberOfSignificantValueDigits must be between 0 and 5" }
    }

    // 字段设为 internal @PublishedApi 以便 inline 函数直接无阻碍访问，避免生成合成访问器

    /**
     * 最低可辨识值的二进制数量级，用于确定 Histogram 内部的基础数值尺度即所有内部计算都会先除以这个基础缩放因子（通过右移实现）。
     */
    val unitMagnitude = lowestDiscernibleValue.floorLog2()

    /**
     * 根据用户要求的有效数字精度，计算一个 bucket 至少需要多少个 sub-bucket，并将这个数量向上取整到最接近的 2 的幂即数量级/指数
     */
    val subBucketCountMagnitude = (2L * powerOf10(numberOfSignificantValueDigits)).ceilLog2()

    /**
     * 子桶数量的一半即假设子桶总数 N = 2^k 得出 子桶总数的一半就是 N/2 = 2^K / 2
     */
    val subBucketHalfCountMagnitude = subBucketCountMagnitude - 1

    /**
     * 1 二进制中就是 01 左移子桶的指数得出子桶的数量
     */
    val subBucketCount = 1L shl subBucketCountMagnitude

    /**
     * 子桶总数的一半：将总数量右移 1 位 等于 (因为子桶永远是 2 的整数次方或者说偶数 所以结尾一定是 0 也就是说右移等于除以 2 )
     *（转换为 Int，因为子桶半数最大仅 13 万左右（numberOfSignificantValueDigits 最大值 5， 2*10^5），远小于 Int.MAX_VALUE）
     */
    val subBucketHalfCount = (subBucketCount shr 1).toInt()

    /**
     * 提取子桶索引的位掩码：用于截断高位防止越界。 而(subBucketCount - 1L) 利用 2^k - 1 产生 k 个连续的 1，再左移 unitMagnitude 位避开低位噪音
     */
    val subBucketMask = (subBucketCount - 1L) shl unitMagnitude

    /**
     * 前导零基准常数：64 位 Long 扣除低位噪声与子桶位后剩余的位数
     */
    val leadingZeroCountBase = Long.SIZE_BITS - unitMagnitude - subBucketCountMagnitude

    /**
     * 大桶总数
     */
    val bucketCount = bucketsNeededToCover(highestTrackableValue)

    /**
     * 物理计数数组的真实长度。
     *
     *  Bucket 0 包含高精度的完整数值区间，需要占用全部子桶空间 (2 * subBucketHalfCount)。
     *  从 Bucket 1 开始，其前半部分数值与前一个 Bucket 的后半部分重合，
     *  按照高精度优先原则，前半部分永远不会有数据写入，因此物理内存中直接剔除前半部分，
     *  后续每个 Bucket 仅需分配后半部分的物理空间 (1 * subBucketHalfCount)。
     *  数组最大元素量约 850 万 (65 * 131072)，绝不会超过 Int.MAX_VALUE (21 亿)，转 Int 绝对安全。
     */
    val countsArrayLength = (bucketCount + 1) * subBucketHalfCount

    inline fun requireRecordable(value: Long) {
        require(value >= 0L)
        require(value <= highestTrackableValue)
    }

    /**
     * 计算覆盖指定 value 所需的 Bucket（桶）总数。
     * 从 Bucket 0 的上限开始，每次左移 1 位（范围翻倍），直到范围能够完全容纳 value 为止。
     */
    internal fun bucketsNeededToCover(value: Long): Int {
        // Bucket 0 能涵盖的不可追踪临界值上限 (subBucketCount * 2^unitMagnitude)
        var smallestUntrackable = subBucketCount shl unitMagnitude
        var result = 1

        while (smallestUntrackable <= value) {
            result++
            // 避免 smallestUntrackable shl 1 发生 Long 溢出
            if (smallestUntrackable > Long.MAX_VALUE / 2L) break
            smallestUntrackable = smallestUntrackable shl 1
        }

        return result
    }

    /**
     * 计算数值对应的大桶 (Bucket) 索引。
     *
     * 【原理】：数值越大，前导零越少。用“基准值 - 前导零个数”可以直接算出桶的索引。
     * 【无分支优化】：
     *  - 使用 `(value or subBucketMask)` 替代 `if (value < bucket0上限)`。
     *  - 作用是给极小值（甚至 0）设立一个“最低高度基准”，强制填充低位。
     *  - 这样即使是 0，计算出的前导零数量也被固定，精准返回 Bucket 0，
     *    彻底消除了 CPU 分支预测失败的开销。
     */
    inline fun bucketIndex(value: Long): Int {
        // 使用 or 即位运算替代 if 避免分支预测的成本
        return leadingZeroCountBase - (value or subBucketMask).countLeadingZeroBits()
    }

    /**
     * 获取指定数值所在大桶的“刻度间距”（即该区间的步长/分辨率）。
     *
     * 【原理】：
     *  - 第 0 桶的最小步长为 2^unitMagnitude。
     *  - 之后桶的索引每增加 1，步长就翻倍一次（即左移 1 位）。
     *  - 该函数算出的步长（2 的幂次），将直接提供给 lowestEquivalentValue，
     *    用于指导该区间内所有的散列数值该如何向下对齐。
     */
    inline fun sizeOfEquivalentValueRange(value: Long): Long =
        1L shl (unitMagnitude + bucketIndex(value))

    /**
     * 将真实的 value 向下抹零对齐到其所在刻度区间的起始点（最低等价值）。
     *
     * 【位运算】：通过按位与 (and) 和取反 (inv) 一刀切除低位零头。
     * 【举例】：假设当前桶的步长为 8，要把 value = 45 对齐到 40：
     *  步长 8 的二进制：`0000 1000`
     *  减 1 得到 7：`0000 0111`（这就找出了所有的“零头位”）
     *  取反 (.inv)：`1111 1000`（造出了一把高位全留、低位抹杀的修剪刀）
     *  45 (二进制 0010 1101) and 修剪刀：结果直接变成了 40 (0010 1000)。
     */
    inline fun lowestEquivalentValue(value: Long): Long =
        value and (sizeOfEquivalentValueRange(value) - 1L).inv()

    /**
     * 获取指定数值所在大桶的“刻度上限”（即该区间的最大值）。
     *
     *  最低值 + 步长 - 1 便是最高值。
     */
    inline fun highestEquivalentValue(value: Long): Long =
        lowestEquivalentValue(value) + sizeOfEquivalentValueRange(value) - 1L

    /**
     * 获取指定数值所在大桶的“刻度中值”（即该区间的中位数）。
     *
     *  最低值 + (步长 / 2) 便是中位数。
     */
    inline fun medianEquivalentValue(value: Long): Double =
        lowestEquivalentValue(value) + sizeOfEquivalentValueRange(value) / 2.0

    /**
     * 将物理计数数组下标反解为它代表的数值区间起点
     */
    inline fun valueFromIndex(index: Int): Long {
        // 半桶块编号 = index / subBucketHalfCount。
        // 因为 subBucketHalfCount 是 2 的幂，用 ushr 快速除法。
        // 再减 1，使块 1 对应 Bucket 0，块 2 对应 Bucket 1，块 0 需要后面特殊修正。
        var bucketIndex = (index ushr subBucketHalfCountMagnitude) - 1

        // 块内偏移 = index % subBucketHalfCount。
        // and (subBucketHalfCount - 1) 等价于对 2 的幂取模。
        // 默认加 subBucketHalfCount，指向“后半子桶”。
        var subBucketIndex =
            (index and (subBucketHalfCount - 1)) + subBucketHalfCount

        // 块编号 0 时，bucketIndex 会变成 -1，说明这是 Bucket 0 的前半块。
        // 修正：子桶索引减掉 subBucketHalfCount，回到前半子桶；桶索引修正为 0。
        if (bucketIndex < 0) {
            subBucketIndex -= subBucketHalfCount
            bucketIndex = 0
        }

        // 实际值 = 子桶索引 × 2^(bucketIndex + unitMagnitude)
        // shl 是左移，等价于乘以 2 的幂。
        return subBucketIndex.toLong() shl (bucketIndex + unitMagnitude)
    }

    /**
     * 计算值在计数数组中的索引。
     */
    inline fun countsArrayIndex(value: Long): Int {
        // 确定值在哪个大桶
        val bucketIndex = bucketIndex(value)
        // 计算值在该大桶中的子桶编号
        val subBucketIndex = (value ushr (bucketIndex + unitMagnitude)).toInt()

        // 将 (bucketIndex, subBucketIndex) 二维坐标扁平化为一维数组索引
        // ((bucketIndex + 1) shl subBucketHalfCountMagnitude) 算出了当前大桶的前缀基准索引
        return ((bucketIndex + 1) shl subBucketHalfCountMagnitude) +
                (subBucketIndex - subBucketHalfCount)
    }

    inline fun computeMean(
        totalCount: Long,
        countsLength: Int,
        getCount: (index: Int) -> Long
    ): Double {
        if (totalCount == 0L) return 0.0

        var total = 0.0
        for (i in 0 until countsLength) {
            val count = getCount(i)
            if (count != 0L) {
                total += medianEquivalentValue(valueFromIndex(i)) * count
            }
        }
        return total / totalCount
    }

    /**
     * 查询满足指定百分位数 [percentile] 的采样测量值。
     */
    inline fun computePercentile(
        percentile: Double,
        totalCount: Long,
        countsLength: Int,
        getCount: (index: Int) -> Long
    ): Long {
        require(percentile in 0.0..100.0)
        if (totalCount == 0L) return 0L

        // 计算达到该百分位所需的最小累加采样次数 (Rank)
        val rank = ceil(percentile * totalCount / 100.0).toLong()
            .coerceAtLeast(1L)

        var seen = 0L
        for (index in 0 until countsLength) {
            seen += getCount(index)
            if (seen >= rank) {
                val value = valueFromIndex(index)
                return if (percentile == 0.0) {
                    lowestEquivalentValue(value)
                } else {
                    highestEquivalentValue(value)
                }
            }
        }

        error("Histogram count state is inconsistent")
    }
}