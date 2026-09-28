@file:Suppress("NOTHING_TO_INLINE")

package io.github.dsqrwym.hdrhistogram

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * 协调多个写者（Writer）线程与单个读者（Reader）线程的双相位无锁同步器。
 *
 * [WriterReaderPhaser] 旨在为写者提供极低开销的记录操作（每次写仅需一次极轻量的原子递增，
 * 无需任何重量级锁或线程阻塞）；同时允许读者线程安全地“翻转相位（Flip Phase）”，
 * 并等待上一个相位中的所有在途写者全部退出临界区后再继续执行。
 *
 * ### 核心模式与工作流：
 * 1. **写者（Writers）**：在进入和退出临界区时，分别调用 [writerCriticalSectionEnter]
 *    与 [writeCriticalSectionExit]。
 * 2. **读者（Reader）**：通过调用 [flipPhase] 切换当前相位，该方法会同步阻塞等待，
 *    直到旧相位中已进入的所有写者全部完成。
 *
 * @note **使用约束**：
 * - 任意时刻**只能有一个线程**调用 [flipPhase]（通常需要由外部锁来保证读者的互斥）。
 * - 读者线程在处于写者临界区内部时，**绝对禁止**调用 [flipPhase]，否则会导致死锁。
 */
@OptIn(ExperimentalAtomicApi::class)
internal class WriterReaderPhaser {
    /**
     * 相位起点，值 >= 0 表示偶数相位，值 < 0 表示奇数相位
     */
    @PublishedApi
    internal val startEpoch = AtomicLong(0L)

    /**
     * 偶数相位的已完成写者数
     */
    @PublishedApi
    internal val evenEndEpoch = AtomicLong(0)

    /**
     * 奇数相位的已完成写者数
     */
    @PublishedApi
    internal val oddEndEpoch = AtomicLong(0)

    /**
     * 写者进入临界区，返回当前所属相位的 ticket
     * ticket >= 0 表示偶数相位，ticket < 0 表示奇数相位
     */
    inline fun writerCriticalSectionEnter(): Long {
        return startEpoch.fetchAndAdd(1L)
    }

    /**
     * 写者退出临界区，根据 ticket 通知对应的 endEpoch
     */
    inline fun writeCriticalSectionExit(ticket: Long) {
        if (ticket < 0L) {
            oddEndEpoch.fetchAndAdd(1L)
        } else {
            evenEndEpoch.fetchAndAdd(1L)
        }
    }

    /**
     * 翻转相位并等待本相位内的所有在途写者退出。
     * 必须在外部读者互斥锁保护下调用，负责两个同时进行会互相干扰。
     */
    fun flipPhase() {
        val nextPhaseIsEvenPhase = startEpoch.load() < 0L

        val initialStarValue: Long
        if (nextPhaseIsEvenPhase) {
            initialStarValue = 0L
            evenEndEpoch.store(initialStarValue)
        } else {
            initialStarValue = Long.MIN_VALUE
            oddEndEpoch.store(initialStarValue)
        }

        // 原子切换相位起点，并拿到翻转那一瞬间旧相位的精确发号数
        val startValueAtFlip = startEpoch.exchange(initialStarValue)

        // 等待旧相位的写者归队
        var caughtUp: Boolean
        do {
            caughtUp = if (nextPhaseIsEvenPhase) {
                // 如果刚从奇相位切过来，就等 oddEndEpoch 赶上翻转瞬间的值
                oddEndEpoch.load() == startValueAtFlip
            } else {
                // 如果刚从偶相位切过来，就等 evenEndEpoch 赶上翻转瞬间的值
                evenEndEpoch.load() == startValueAtFlip
            }
            if (!caughtUp) cpuRelax()
        } while (!caughtUp)
    }
}