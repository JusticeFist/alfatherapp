import java.util.zip.GZIPOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The watch half of AllFather: same applicationId and signing key as the phone app, which is what lets
// the Wearable Data Layer connect the two. It talks only to the phone; the phone talks to Hermes.
android {
    namespace = "app.hermesandroid.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.allfather"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

// Same as the phone app: keep each release's R8 mapping by map id for .tools/crashes.sh.
tasks.matching { it.name == "minifyReleaseWithR8" }.configureEach {
    // AGP writes it under outputs/ or only under intermediates/ depending on the build: take whichever exists.
    val mappings = listOf("outputs/mapping/release/mapping.txt", "intermediates/mapping/release/minifyReleaseWithR8/mapping.txt").map { layout.buildDirectory.file(it) }
    val saved = rootProject.file(".tools/mappings")
    val name = "wear-${android.defaultConfig.versionCode}"
    doLast {
        val f = mappings.map { it.get().asFile }.firstOrNull { it.exists() } ?: return@doLast
        val id = f.useLines { l -> l.take(20).firstOrNull { it.startsWith("# pg_map_id: ") }?.substringAfter(": ") } ?: return@doLast
        saved.mkdirs()
        f.inputStream().use { i -> GZIPOutputStream(saved.resolve("$name-$id.txt.gz").outputStream()).use { i.copyTo(it) } }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.wear.compose:compose-material:1.7.1")
    implementation("androidx.wear.compose:compose-foundation:1.7.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material:1.4.2")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")
    implementation("com.google.guava:guava:33.7.2-android")
    implementation("androidx.wear:wear-input:1.2.0")
    implementation("androidx.core:core-ktx:1.19.1")
}
