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

    signAllPublications()

    coordinates(group.toString(), "library.hdrhistogram", version.toString())

    pom {
        name = "My library"
        description = "A library."
        inceptionYear = "2024"
        url = "https://github.com/kotlin/multiplatform-library-template/"
        licenses {
            license {
                name = "XXX"
                url = "YYY"
                distribution = "ZZZ"
            }
        }
        developers {
            developer {
                id = "XXX"
                name = "YYY"
                url = "ZZZ"
            }
        }
        scm {
            url = "XXX"
            connection = "YYY"
            developerConnection = "ZZZ"
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


