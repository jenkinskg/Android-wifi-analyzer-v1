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
        versionCode = 5
        versionName = "0.5-IMA"
    }
}
dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
}
kotlin { jvmToolchain(17) }