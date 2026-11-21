package test

import io.github.dsqrwym.hdrhistogram.AtomicHistogram
import io.github.dsqrwym.hdrhistogram.Histogram
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `estimatedFootprintInBytes` 的契约测试。
 */
class EstimatedFootprintTest {

    @Test
    fun formulaIsCountsArrayPlusFixedOverhead() {
        val histogram = Histogram(1L, 10_000_000L, 3)
        assertEquals(
            histogram.countsArrayLength.toLong() * 8L + 64L,
            histogram.estimatedFootprintInBytes,
        )
    }

    @Test
    fun footprintIsConstantAfterRecording() {
        val histogram = Histogram(1L, 3_600_000_000L, 3)
        val initial = histogram.estimatedFootprintInBytes

        for (i in 1..10_000) {
            histogram.recordValue(i.toLong())
        }

        assertEquals(initial, histogram.estimatedFootprintInBytes)
    }

    @Test
    fun atomicHistogramUsesSameEstimate() {
        val plain = Histogram(1L, 10_000_000L, 3)
        val atomic = AtomicHistogram(1L, 10_000_000L, 3)

        assertEquals(plain.estimatedFootprintInBytes, atomic.estimatedFootprintInBytes)
    }
}