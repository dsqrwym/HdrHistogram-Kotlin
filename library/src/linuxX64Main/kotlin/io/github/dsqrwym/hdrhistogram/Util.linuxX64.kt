package io.github.dsqrwym.hdrhistogram

import io.github.dsqrwym.hdrhistogram.cinterop.kmp_cpu_relax
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
internal actual inline fun cpuRelax() {
    kmp_cpu_relax()
}