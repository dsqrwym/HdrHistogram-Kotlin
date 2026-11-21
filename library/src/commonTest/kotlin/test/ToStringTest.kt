package test

import io.github.dsqrwym.hdrhistogram.Histogram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `toString()` 的可读摘要契约。
 *
 * 不校验官方 Java 的逐槽 dump 格式；只锁布局、关键统计和空图安全性。
 */
class ToStringTest {

    private fun h() = Histogram(1L, 1_000L, 3)

    @Test
    fun emptyHistogramIsSafe() {
        val text = h().toString()

        assertTrue(text.contains("lowest=1"), text)
        assertTrue(text.contains("highest=1000"), text)
        assertTrue(text.contains("digits=3"), text)
        assertTrue(text.contains("total=0"), text)
        assertTrue(text.contains("min=0"), text)
        assertTrue(text.contains("max=0"), text)
        // Double.toString() 在 JVM 上是 "0.0"，在 JS/Wasm 上是 "0"；断言共同前缀。
        assertTrue(text.contains("mean=0"), text)
        assertTrue(text.contains("p50=0"), text)
        assertTrue(text.contains("p99=0"), text)
        assertTrue(text.contains("p999=0"), text)
        // 回归：曾经误写成 "(lowest=)"
        assertTrue(!text.contains("(lowest=)"), text)
    }

    @Test
    fun containsLayoutAndStats() {
        val histogram = h()
        histogram.recordValue(100L)
        histogram.recordValue(200L)

        val text = histogram.toString()

        for (key in listOf(
            "lowest=", "highest=", "digits=", "buckets=", "subBuckets=", "counts=",
            "total=", "min=", "max=", "mean=", "p50=", "p99=", "p999=",
        )) {
            assertTrue(text.contains(key), "missing $key in: $text")
        }
        assertTrue(text.contains("total=2"), text)
        assertTrue(text.contains("min=100"), text)
        assertTrue(text.contains("max=200"), text)
        assertTrue(text.contains("p50=100"), text)
        assertTrue(text.contains("p99=200"), text)
    }

    @Test
    fun sameDataProducesSameString() {
        val first = h()
        val second = h()
        first.recordValue(100L)
        second.recordValue(100L)

        assertEquals(first.toString(), second.toString())
    }

    @Test
    fun copyProducesSameString() {
        val original = h()
        original.recordValue(100L)
        original.recordValue(200L)

        assertEquals(original.toString(), original.copy().toString())
    }
}