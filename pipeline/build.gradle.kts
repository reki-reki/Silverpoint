plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.silverpoint.pipeline"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
    }

    testOptions {
        unitTests.all {
            // The Lightroom fit develops a full 12.5 MP frame on the CPU.
            it.maxHeapSize = "4g"
            // Dev-only inputs for LightroomFitTest; the test is skipped when they are absent.
            System.getenv("SILVERPOINT_FULL_FIXTURE")?.let { dir -> it.systemProperty("silverpoint.fullFixture", dir) }
            // Dev-only inputs for RecipeMatchFitTest (Silverpoint/Lightroom photo pairs as block samples).
            System.getenv("SILVERPOINT_MATCH_SAMPLES")?.let { dir -> it.systemProperty("silverpoint.matchSamples", dir) }
            // Dev-only output for GpuParityDumpTest (self-test cases for scripts/gpu_parity.py).
            System.getenv("SILVERPOINT_GPU_PARITY")?.let { dir -> it.systemProperty("silverpoint.gpuParity", dir) }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":recipes"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}
