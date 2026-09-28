package io.github.dsqrwym.hdrhistogram

import android.os.Build

private val isOnSpinWaitSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
internal actual inline fun cpuRelax() {
    if (isOnSpinWaitSupported) {
        Thread.onSpinWait()
    }
}