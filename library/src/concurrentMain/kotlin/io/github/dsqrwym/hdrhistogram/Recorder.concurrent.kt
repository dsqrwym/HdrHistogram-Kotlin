package io.github.dsqrwym.hdrhistogram

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
actual class Recorder actual constructor(
    val lowestDiscernibleValue: Long,
    val highestTrackableValue: Long,
    val numberOfSignificantValueDigits: Int
) : SynchronizedObject() {
    private val phaser = WriterReaderPhaser()

    // 双缓冲内部储存
    private var activeHistogram =
        AtomicHistogram(lowestDiscernibleValue, highestTrackableValue, numberOfSignificantValueDigits)
    private var inactiveHistogram =
        AtomicHistogram(lowestDiscernibleValue, highestTrackableValue, numberOfSignificantValueDigits)

    private val activeRef = AtomicReference(activeHistogram)

    actual fun recordValue(value: Long, count: Long) {
        val ticket = phaser.writerCriticalSectionEnter()
        try {
            activeRef.load().recordValue(value, count)
        } finally {
            phaser.writeCriticalSectionExit(ticket)
        }
    }

    actual fun getIntervalHistogram(histogramToRecycle: Histogram?): Histogram = synchronized(this) {
        val target = histogramToRecycle ?: Histogram(
            lowestDiscernibleValue,
            highestTrackableValue,
            numberOfSignificantValueDigits
        )
        target.reset()

        // 互换活跃桶引用
        val temp = inactiveHistogram
        inactiveHistogram = activeHistogram
        activeHistogram = temp

        // 将新的桶暴露给写者
        activeRef.store(activeHistogram)

        // 相位翻转，等待旧桶写入结束
        phaser.flipPhase()

        target.add(inactiveHistogram)

        inactiveHistogram.reset()

        return target
    }

}
