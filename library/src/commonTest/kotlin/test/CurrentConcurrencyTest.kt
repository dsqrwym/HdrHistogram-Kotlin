package test

import io.github.dsqrwym.hdrhistogram.Histogram
import io.github.dsqrwym.hdrhistogram.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class CurrentConcurrencyTest {
    @Test
    fun testConcurrentRecordingAndIntervalAccounting() = runTest {
        val recorder = Recorder(1, 100_000, 3)

        val threadCount = 8
        val writePerThread = 50_000
        val totalExpectedWrites = (threadCount * writePerThread).toLong()

        val cumulativeHistogram = Histogram(1, 100_00, 3)
        var isWritingCompleted = false

        val readerJob = launch(Dispatchers.Default) {
            val recycle = Histogram(1, 100_00, 3)
            while (!isWritingCompleted) {
                delay(10.milliseconds)
                cumulativeHistogram.add(recorder.getIntervalHistogram(recycle))
            }

            cumulativeHistogram.add(recorder.getIntervalHistogram(recycle))
        }

        val writerJobs = List(threadCount) {
            launch(Dispatchers.Default) {
                for (i in 1..writePerThread) {
                    // 记录一个合法范围内的延迟值
                    val latency = ((i % 1000) + 1).toLong()
                    recorder.recordValue(latency)
                }
            }
        }

        writerJobs.joinAll()
        isWritingCompleted = true
        readerJob.join()

        print("Total expected writes: $totalExpectedWrites, actual writes: ${cumulativeHistogram.totalCount}")
        assertEquals(
            totalExpectedWrites,
            cumulativeHistogram.totalCount,
            "Data loss or spoofing occurred during concurrent writes and flips"
        )
    }
}