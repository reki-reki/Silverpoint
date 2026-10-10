plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.silverpoint"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.silverpoint.camera"
        minSdk = 34
        targetSdk = 36
        versionCode = 18
        versionName = "0.9.9"
    }

    // Release signing comes from the environment (or ~/.gradle/gradle.properties), never from the
    // repository: SILVERPOINT_KEYSTORE (path), SILVERPOINT_KEYSTORE_PASSWORD, SILVERPOINT_KEY_ALIAS,
    // SILVERPOINT_KEY_PASSWORD. Without them the release APK is built unsigned. See docs/RELEASING.md.
    fun secret(name: String): String? = System.getenv(name) ?: providers.gradleProperty(name).orNull
    val keystore = secret("SILVERPOINT_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = secret("SILVERPOINT_KEYSTORE_PASSWORD")
            keyAlias = secret("SILVERPOINT_KEY_ALIAS")
            keyPassword = secret("SILVERPOINT_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8: drops unused code and resources (the APK is several times smaller). The app uses no
            // reflection beyond Kotlin property delegates, so the default rules are enough.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric renders the Compose screens for the dev-only screenshot tests.
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "3g"
                // Robolectric reaches into these JDK internals.
                it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
                // Screenshot and motion tests need Robolectric's 100+ MB Android jar: only when asked for.
                if (System.getenv("SILVERPOINT_SCREENSHOTS") == null) {
                    it.exclude("**/ScreenshotTest*")
                    it.exclude("**/MotionTest*")
                }
                System.getenv("SILVERPOINT_SCREENSHOTS")?.let { dir ->
                    it.systemProperty("silverpoint.screenshots", dir)
                    it.systemProperty("roborazzi.test.record", "true")
                }
                // Where Robolectric downloads its Android jar, if Maven Central is not reachable.
                System.getenv("ROBOLECTRIC_REPO_URL")?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url) }
            }
        }
    }
}

dependencies {
    implementation(project(":camera"))
    implementation(project(":pipeline"))
    implementation(project(":recipes"))
    implementation(project(":storage"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
