plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.eggwatch.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.play.services.wearable)
    api(libs.kotlinx.coroutines.play.services)
}
