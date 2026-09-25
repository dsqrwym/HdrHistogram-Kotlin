@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import kotlinx.benchmark.gradle.JsBenchmarkTarget
import kotlinx.benchmark.gradle.JsBenchmarksExecutor
import kotlinx.benchmark.gradle.benchmark
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.kotlinxBenchmark)
    alias(libs.plugins.kotlin.allopen)
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

group = "io.github.dsqrwym"
version = "0.0.1"

kotlin {
    val commonBenchmark by sourceSets.creating {
        dependencies {
            implementation(libs.kotlinx.benchmark.runtime)
        }
    }

    jvm {
        compilations.create("benchmark") {
            associateWith(this@jvm.compilations.getByName("main"))
            defaultSourceSet.dependsOn(commonBenchmark)
        }
    }



    android {
        namespace = "io.github.dsqrwym.library.hdrhistogram"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    iosArm64()
    iosSimulatorArm64()
    linuxX64()

    js {
        nodejs()
        compilations.create("benchmark") {
            associateWith(this@js.compilations.getByName("main"))
            defaultSourceSet.dependsOn(commonBenchmark)
        }
    }

    wasmJs {
        nodejs()
        compilations.create("benchmark") {
            associateWith(this@wasmJs.compilations.getByName("main"))
            defaultSourceSet.dependsOn(commonBenchmark)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Core buffer library
            implementation(libs.buffer)
            implementation(libs.kotlinx.benchmark.runtime)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}



mavenPublishing {
    publishToMavenCentral()

    // 只要存在内存签名密钥或文件签名密钥才开启签名
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent) {
        signAllPublications()
    }

    coordinates(group.toString(), "hdrhistogram-kotlin", version.toString())

    pom {
        name = "HdrHistogram Kotlin"
        description = "A High Dynamic Range (HDR) Histogram implementation for Kotlin Multiplatform."
        inceptionYear = "2026"
        url = "https://github.com/dsqrwym/HdrHistogram-Kotlin/"
        licenses {
            license {
                name = "Apache-2.0"
                url = "http://www.apache.org/licenses/"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "dsqrwym"
                name = "dsqrwym"
                url = "https://github.com/dsqrwym"
            }
        }
        scm {
            url = "https://github.com/dsqrwym/HdrHistogram-Kotlin"
            connection = "scm:git:git://github.com/dsqrwym/HdrHistogram-Kotlin.git"
            developerConnection = "scm:git:ssh://git@github.com:dsqrwym/HdrHistogram-Kotlin.git"
        }
    }
}

benchmark {
    targets {
        register("jvmBenchmark")
        register("wasmJsBenchmark")
        register("jsBenchmark") {
            this as JsBenchmarkTarget
            jsBenchmarksExecutor = JsBenchmarksExecutor.BuiltIn
        }
    }
}


