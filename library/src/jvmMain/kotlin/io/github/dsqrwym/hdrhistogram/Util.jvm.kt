package io.github.dsqrwym.hdrhistogram

internal actual inline fun cpuRelax() {
    Thread.onSpinWait()
}