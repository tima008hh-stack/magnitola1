plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.suptek.carlauncher"
    compileSdk = 34
    defaultConfig { applicationId = "com.suptek.carlauncher"; minSdk = 24; targetSdk = 28; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
