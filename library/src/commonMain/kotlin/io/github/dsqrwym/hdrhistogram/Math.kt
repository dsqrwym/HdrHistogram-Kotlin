package io.github.dsqrwym.hdrhistogram

/**
 * 计算值的 log₂ 并向下取整
 * 10 的二进制 (64位):   000...000 1010  (最高位 '1' 在第 3 位，对应 2^3 = 8)
 * 前导零个数        :   60 个
 * 计算过程         :   63 - 60 = 3  => log₂(10) = 3
 */
internal fun Long.floorLog2(): Int {
    return Long.SIZE_BITS - 1 - this.countLeadingZeroBits()
}

/**
 * 计算值的 log₂ 并向上取整
 * 当 x <= 1 时：log₂(1) = 0，非正数无对数意义，直接归零返回 0
 * 当 x > 1 时：
 *      先将 x 减 1：解决 x 恰好为 2 的幂次时的边界问题（例如 8-1=7，避免 8 被错算为 4）。
 *      countLeadingZeroBits()：计算 (x - 1) 的二进制前导零个数。
 *      64 - 前导零个数：即可得出表示 (x - 1) 所需的最小总位数，恰好等于 log₂(x)
 */
internal fun Long.ceilLog2(): Int {
    // 不需要避免分支预测的成本，因为概率太小
    return if (this <= 1L) 0 else Long.SIZE_BITS - (this - 1L).countLeadingZeroBits()
}

internal fun powerOf10(exponent: Int): Long {
    require(exponent in 0..18) { "Exponent out of bounds for Long: $exponent" }
    return POWERS_OF_10[exponent]
}

// 预先算好 10^0 到 10^18，避免运行时重复计算
private val POWERS_OF_10 = longArrayOf(
    1L,                     // 10^0
    10L,                    // 10^1
    100L,                   // 10^2
    1000L,                  // 10^3
    10000L,                 // 10^4
    100000L,                // 10^5
    1000000L,               // 10^6
    10000000L,              // 10^7
    100000000L,             // 10^8
    1000000000L,            // 10^9
    10000000000L,           // 10^10
    100000000000L,          // 10^11
    1000000000000L,         // 10^12
    10000000000000L,        // 10^13
    100000000000000L,       // 10^14
    1000000000000000L,      // 10^15
    10000000000000000L,     // 10^16
    100000000000000000L,    // 10^17
    1000000000000000000L    // 10^18
)