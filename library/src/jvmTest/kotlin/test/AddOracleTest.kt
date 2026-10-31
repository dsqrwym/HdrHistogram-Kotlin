package test

import io.github.dsqrwym.hdrhistogram.Histogram
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AddOracleTest {

    private fun nextValue(seed: Long): Long =
        (seed * 6364136223846793005L + 1442695040888963407L) ushr 33

    private fun allocatedBytes(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    @Test
    fun sameLayoutMatchesOfficial() {
        val ktTarget = Histogram(1L, 1_000_000L, 3)
        val ktSource = Histogram(1L, 1_000_000L, 3)
        val javaTarget = org.HdrHistogram.Histogram(1L, 1_000_000L, 3)
        val javaSource = org.HdrHistogram.Histogram(1L, 1_000_000L, 3)

        var seed = 42L
        repeat(5_000) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val value = nextValue(seed) % 1_000_000L
            ktSource.recordValue(value)
            javaSource.recordValue(value)
        }

        ktTarget.add(ktSource)
        javaTarget.add(javaSource)

        assertEquals(javaTarget.totalCount, ktTarget.totalCount)
        assertEquals(javaTarget.maxValue, ktTarget.maxValue)
        assertEquals(javaTarget.minValue, ktTarget.minValue)
        assertEquals(javaTarget.minNonZeroValue, ktTarget.minNonZeroValue)

        for (i in 0 until ktTarget.countsArrayLength) {
            val value = ktTarget.valueFromIndex(i)
            if (value > 1_000_000L) continue
            assertEquals(javaTarget.getCountAtValue(value), ktTarget.countAtIndex(i), "slot $i / value $value")
        }
    }

    @Test
    fun differentLayoutMatchesOfficial() {
        val ktTarget = Histogram(1L, 10_000_000L, 3)
        val ktSource = Histogram(1L, 1_000_000L, 3)
        val javaTarget = org.HdrHistogram.Histogram(1L, 10_000_000L, 3)
        val javaSource = org.HdrHistogram.Histogram(1L, 1_000_000L, 3)

        var seed = 7L
        repeat(5_000) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val value = nextValue(seed) % 1_000_000L
            ktSource.recordValue(value)
            javaSource.recordValue(value)
        }

        ktTarget.add(ktSource)
        javaTarget.add(javaSource)

        assertEquals(javaTarget.totalCount, ktTarget.totalCount)
        assertEquals(javaTarget.maxValue, ktTarget.maxValue)
        assertEquals(javaTarget.minValue, ktTarget.minValue)
        assertEquals(javaTarget.minNonZeroValue, ktTarget.minNonZeroValue)

        for (i in 0 until ktTarget.countsArrayLength) {
            val value = ktTarget.valueFromIndex(i)
            if (value > 1_000_000L) continue
            assertEquals(javaTarget.getCountAtValue(value), ktTarget.countAtIndex(i), "slot $i / value $value")
        }
    }

    @Test
    fun overflowRejectedLikeOfficial() {
        val ktTarget = Histogram(1L, 1_000_000L, 3)
        val ktSource = Histogram(1L, 10_000_000L, 3)
        ktSource.recordValue(5_000_000L)
        assertFailsWith<IllegalArgumentException> { ktTarget.add(ktSource) }

        val javaTarget = org.HdrHistogram.Histogram(1L, 1_000_000L, 3)
        val javaSource = org.HdrHistogram.Histogram(1L, 10_000_000L, 3)
        javaSource.recordValue(5_000_000L)
        assertFailsWith<ArrayIndexOutOfBoundsException> { javaTarget.add(javaSource) }
    }

    @Test
    fun mergeIsAllocationFree() {
        val target = Histogram(1L, 1_000_000L, 3)
        val source = Histogram(1L, 1_000_000L, 3)
        repeat(1_000) { source.recordValue(it * 900L + 1) }

        repeat(2_000) { target.add(source) }
        val window1k = allocatedBytes { repeat(1_000) { target.add(source) } }
        val window10k = allocatedBytes { repeat(10_000) { target.add(source) } }
        val perOp = (window10k - window1k) / 9_000.0
        println("add merge allocation: 1k=$window1k B, 10k=$window10k B -> $perOp B/op")
        assertTrue(perOp < 0.01, "add must be allocation free (actual: $perOp B/op)")
    }

    /**
     * 源的最大值落在目标的 (highestTrackableValue, highestRecordableValue] 区间。
     * 官方可以正常合并，本项目必须与之完全一致。
     */
    @Test
    fun lastBucketValuesMatchOfficial() {
        val ktTarget = Histogram(1L, 1_000_000L, 3)
        val ktSource = Histogram(1L, 2_000_000L, 3)
        ktSource.recordValue(1_020_000L)

        val javaTarget = org.HdrHistogram.Histogram(1L, 1_000_000L, 3)
        val javaSource = org.HdrHistogram.Histogram(1L, 2_000_000L, 3)
        javaSource.recordValue(1_020_000L)

        ktTarget.add(ktSource)
        javaTarget.add(javaSource)

        assertEquals(javaTarget.totalCount, ktTarget.totalCount)
        assertEquals(javaTarget.maxValue, ktTarget.maxValue)
        assertEquals(javaTarget.minValue, ktTarget.minValue)
        assertEquals(javaTarget.minNonZeroValue, ktTarget.minNonZeroValue)

        val slot = ktTarget.countsArrayIndex(1_019_904L)
        assertEquals(1L, ktTarget.countAtIndex(slot))
        assertEquals(javaTarget.getCountAtValue(javaTarget.maxValue), ktTarget.countAtIndex(slot))
    }
}
