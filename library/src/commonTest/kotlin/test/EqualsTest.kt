package test

import io.github.dsqrwym.hdrhistogram.AtomicHistogram
import io.github.dsqrwym.hdrhistogram.Histogram
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalAtomicApi::class)
class EqualsTest {

    private fun histogram(vararg values: Long, lowest: Long = 1L, digits: Int = 3): Histogram =
        Histogram(lowest, 1_000_000L, digits).apply { values.forEach { recordValue(it) } }

    @Test
    fun equalsSameContent() {
        assertEquals(histogram(1, 2, 3), histogram(1, 2, 3))
    }

    @Test
    fun notEqualsDifferentContent() {
        assertFalse(histogram(1, 2, 3) == histogram(1, 2))
    }

    @Test
    fun notEqualsDifferentPrecision() {
        val a = histogram(1_000, 2_000, digits = 3)
        val b = histogram(1_000, 2_000, digits = 2)
        assertFalse(a == b)
    }

    @Test
    fun notEqualsDifferentLowestDiscernible() {
        val a = histogram(1_000, 2_000, lowest = 1L)
        val b = histogram(1_000, 2_000, lowest = 2L)
        assertFalse(a == b)
    }

    @Test
    fun equalsIsReflexive() {
        val a = histogram(1, 2, 3)
        assertTrue(a == a)
    }

    @Test
    fun equalsAcrossHistogramTypes() {
        val plain = histogram(1, 2, 3)
        val atomic = AtomicHistogram(1L, 1_000_000L, 3).apply {
            recordValue(1L)
            recordValue(2L)
            recordValue(3L)
        }
        assertTrue(plain == atomic)
        assertTrue(atomic == plain)
    }

    @Test
    fun hashCodeAgreesWithEquals() {
        val a = histogram(1, 2, 3)
        val b = histogram(1, 2, 3)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    /** 5000 与 5001 在 lowest=1、3 位精度下落在同一个槽，对外部可观测状态完全等价。 */
    @Test
    fun equalsTreatsSameSlotValuesAsEqual() {
        val a = histogram(5_000L)
        val b = histogram(5_001L)

        assertEquals(a.minNonZeroValue, b.minNonZeroValue)
        assertEquals(a.maxValue, b.maxValue)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        // 5004 是下一个槽的起点，必须不相等
        assertFalse(a == histogram(5_004L))
    }
}
