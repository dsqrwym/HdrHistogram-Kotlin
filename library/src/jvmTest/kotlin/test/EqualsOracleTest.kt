package test

import io.github.dsqrwym.hdrhistogram.Histogram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EqualsOracleTest {

    private fun kt(values: List<Long>, digits: Int = 3): Histogram =
        Histogram(1L, 1_000_000L, digits).apply { values.forEach { recordValue(it) } }

    private fun java(values: List<Long>, digits: Int = 3): org.HdrHistogram.Histogram =
        org.HdrHistogram.Histogram(1L, 1_000_000L, digits).apply { values.forEach { recordValue(it) } }

    @Test
    fun equalsMatchesOfficial() {
        val values = (1..500).map { it * 1_000L }

        // 相同内容：两边各自创建两个独立实例
        assertEquals(java(values) == java(values), kt(values) == kt(values))

        // 差一个样本
        assertEquals(java(values.dropLast(1)) == java(values), kt(values.dropLast(1)) == kt(values))
        assertFalse(kt(values.dropLast(1)) == kt(values))

        // 精度不同
        assertEquals(java(values, 2) == java(values, 3), kt(values, 2) == kt(values, 3))
        assertFalse(kt(values, 2) == kt(values, 3))
    }

    @Test
    fun hashCodeMatchesOfficial() {
        val values = (1..500).map { it * 1_000L }
        assertEquals(java(values).hashCode(), kt(values).hashCode())

        assertEquals(
            org.HdrHistogram.Histogram(1L, 1_000_000L, 3).hashCode(),
            Histogram(1L, 1_000_000L, 3).hashCode()
        )
    }

    @Test
    fun minNonZeroValueMatchesOfficial() {
        listOf(1L, 500L, 1_500L, 5_000L, 5_001L, 100_000L, 999_999L).forEach { value ->
            val ktHistogram = kt(listOf(value))
            val javaHistogram = java(listOf(value))
            assertEquals(
                javaHistogram.minNonZeroValue,
                ktHistogram.minNonZeroValue,
                "minNonZeroValue mismatch for recorded value $value"
            )
        }

        // 空直方图：双方都返回 Long.MAX_VALUE
        assertEquals(
            org.HdrHistogram.Histogram(1L, 1_000_000L, 3).minNonZeroValue,
            Histogram(1L, 1_000_000L, 3).minNonZeroValue
        )
    }

    @Test
    fun equalsSameSlotValuesMatchesOfficial() {
        // 5000 与 5001 在该配置下落在同一个槽，双方都应判为相等
        assertEquals(java(listOf(5_000L)) == java(listOf(5_001L)), kt(listOf(5_000L)) == kt(listOf(5_001L)))
        assertTrue(kt(listOf(5_000L)) == kt(listOf(5_001L)))

        // 5004 是下一个槽的起点，双方都应判为不等
        assertEquals(java(listOf(5_000L)) == java(listOf(5_004L)), kt(listOf(5_000L)) == kt(listOf(5_004L)))
        assertFalse(kt(listOf(5_000L)) == kt(listOf(5_004L)))
    }
}
