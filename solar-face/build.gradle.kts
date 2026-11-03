plugins { id("com.android.application") }
android {
    namespace = "com.anand.envoymonitor.face"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.anand.envoymonitor.face"
        minSdk = 33
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"
    }
    buildTypes {
        // Remove AGP's generated R class while retaining every WFF resource.
        getByName("debug") { isMinifyEnabled = true; isShrinkResources = false }
        getByName("release") { isMinifyEnabled = true; isShrinkResources = false }
    }
}
