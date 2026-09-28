plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.keith.obd2scanner"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.keith.obd2scanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }
}
kotlin { jvmToolchain(17) }
