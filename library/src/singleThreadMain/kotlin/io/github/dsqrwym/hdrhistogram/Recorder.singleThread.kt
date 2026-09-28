package io.github.dsqrwym.hdrhistogram

actual class Recorder actual constructor(
    lowestDiscernibleValue: Long,
    highestTrackableValue: Long,
    numberOfSignificantValueDigits: Int
) {
    private val lowestDiscernibleValueInternal = lowestDiscernibleValue
    private val highestTrackableValueInternal = highestTrackableValue
    private val numberOfSignificantValueDigitsInternal = numberOfSignificantValueDigits

    private var activeHistogram = Histogram(lowestDiscernibleValue, highestTrackableValue, numberOfSignificantValueDigits)
    private var inactiveHistogram = Histogram(lowestDiscernibleValue, highestTrackableValue, numberOfSignificantValueDigits)

    actual fun recordValue(value: Long, count: Long) {
        activeHistogram.recordValue(value, count)
    }

    actual fun getIntervalHistogram(histogramToRecycle: Histogram?): Histogram {
        val target = histogramToRecycle ?: Histogram(
            lowestDiscernibleValueInternal,
            highestTrackableValueInternal,
            numberOfSignificantValueDigitsInternal
        )
        target.reset()

        val temp = inactiveHistogram
        inactiveHistogram = activeHistogram
        activeHistogram = temp

        target.add(inactiveHistogram)
        inactiveHistogram.reset()

        return target
    }
}
