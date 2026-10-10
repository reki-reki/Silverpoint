plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.silverpoint.storage"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.exifinterface)
    testImplementation(libs.junit)
}
