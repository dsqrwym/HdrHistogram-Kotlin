package test

import io.github.dsqrwym.hdrhistogram.AtomicHistogram
import io.github.dsqrwym.hdrhistogram.Histogram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * `copy()` / `copyInto()` 的契约测试。
 */
class CopyTest {

    private fun h() = Histogram(1L, 1_000L, 3)
    private fun ah() = AtomicHistogram(1L, 1_000L, 3)

    @Test
    fun copyEqualsOriginal() {
        val original = h()
        original.recordValue(100L)
        original.recordValue(1_001L)

        val copy = original.copy()

        assertEquals(original, copy)
        assertEquals(original.hashCode(), copy.hashCode())
        assertEquals(original.totalCount, copy.totalCount)
        assertEquals(original.maxValue, copy.maxValue)
        assertEquals(original.minNonZeroValue, copy.minNonZeroValue)
    }

    @Test
    fun copyIsIndependent() {
        val original = h()
        original.recordValue(100L)

        val copy = original.copy()
        copy.recordValue(200L)

        assertEquals(1L, original.totalCount)
        assertEquals(2L, copy.totalCount)
        assertNotEquals(original, copy)
    }

    @Test
    fun copyEmptyHistogram() {
        val copy = h().copy()
        assertEquals(0L, copy.totalCount)
        assertEquals(0L, copy.maxValue)
        assertEquals(0.0, copy.mean)
    }

    @Test
    fun copyIntoOverwritesTarget() {
        val source = h()
        source.recordValue(123L)
        source.recordValue(456L)

        val target = h()
        target.recordValue(999L)

        source.copyInto(target)

        assertEquals(source, target)
        assertEquals(2L, target.totalCount)
        assertEquals(0L, target.countAtValue(999L))
    }

    @Test
    fun copyIntoSelfThrows() {
        val histogram = h()
        histogram.recordValue(100L)

        assertFailsWith<IllegalArgumentException> {
            histogram.copyInto(histogram)
        }
        // 失败后原数据必须仍然存在
        assertEquals(1L, histogram.totalCount)
    }

    @Test
    fun copyIntoDifferentLayoutUsesValuePath() {
        val source = Histogram(1L, 1_000L, 3)
        source.recordValue(1_001L) // source 的 gap 值

        val target = Histogram(1L, 10_000L, 3)
        target.recordValue(999L)

        source.copyInto(target)

        assertEquals(source.totalCount, target.totalCount)
        assertEquals(1L, target.countAtValue(1_001L))
        assertEquals(0L, target.countAtValue(999L))
        assertEquals(source.valueAtPercentile(100.0), target.valueAtPercentile(100.0))
    }

    @Test
    fun atomicCopyEqualsOriginalAndIsIndependent() {
        val original = ah()
        original.recordValue(100L)
        original.recordValue(200L)

        val copy = original.copy()

        assertEquals(original, copy)
        assertEquals(2L, copy.totalCount)

        copy.recordValue(300L)

        assertEquals(2L, original.totalCount)
        assertEquals(3L, copy.totalCount)
    }

    @Test
    fun copyBetweenImplementations() {
        val source = h()
        source.recordValue(100L)

        val target = ah()
        source.copyInto(target)

        assertEquals(source.totalCount, target.totalCount)
        assertEquals(source.maxValue, target.maxValue)
        assertEquals(1L, target.countAtValue(100L))
    }
}