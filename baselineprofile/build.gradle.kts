plugins {
    id("com.android.test")
}

// Generates app/src/main/baseline-prof.txt on the phone: run .tools/baseline.sh, never connectedAndroidTest
// (that uninstalls app.hermesandroid afterwards and wipes the sign-in, push and sharing keys).
android {
    namespace = "app.hermesandroid.baselineprofile"
    compileSdk = 37
    targetProjectPath = ":app"
    // The test APK instruments itself and drives the release-like app from outside, like a user.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("nonMinifiedRelease") {
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
