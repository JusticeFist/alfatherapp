import java.util.zip.GZIPOutputStream

// Firebase options read from google-services.json (the user's own project) instead of the Google Services
// Gradle plugin; without the file FCM stays off and the app still builds.
val firebase: Map<String, String> = file("google-services.json").takeIf { it.exists() }?.let { f ->
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
    val project = json["project_info"] as Map<String, Any?>
    val client = (json["client"] as List<Map<String, Any?>>).first {
        ((it["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "app.allfather"
    }
    mapOf(
        "FCM_APP_ID" to (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"].toString(),
        "FCM_API_KEY" to ((client["api_key"] as List<Map<String, Any?>>).first()["current_key"]).toString(),
        "FCM_PROJECT_ID" to project["project_id"].toString(),
        "FCM_SENDER_ID" to project["project_number"].toString(),
    )
} ?: emptyMap()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.hermesandroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.allfather"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        listOf("FCM_APP_ID", "FCM_API_KEY", "FCM_PROJECT_ID", "FCM_SENDER_ID").forEach { buildConfigField("String", it, "\"${firebase[it].orEmpty()}\"") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // ponytail: release signed with the debug key until there's a Play Store listing
            signingConfig = signingConfigs.getByName("debug")
        }
        // Baseline Profile collection only (.tools/baseline.sh): release without R8, so the profile names real
        // classes; profileable from src/nonMinifiedRelease. Same id and key: it installs over the app keeping its data.
        create("nonMinifiedRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    // Robolectric downloads its Android jars into a Maven folder: keep it in .tools with the rest of the tooling.
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all { it.systemProperty("maven.repo.local", rootProject.file(".tools/robolectric-m2").path) }
}

// Every release's R8 mapping, named by its map id (crash traces print `r8-map-id-<id>:<line>`), so
// .tools/crashes.sh can de-obfuscate a crash from any installed version.
tasks.matching { it.name == "minifyReleaseWithR8" }.configureEach {
    // AGP writes it under outputs/ or only under intermediates/ depending on the build: take whichever exists.
    val mappings = listOf("outputs/mapping/release/mapping.txt", "intermediates/mapping/release/minifyReleaseWithR8/mapping.txt").map { layout.buildDirectory.file(it) }
    val saved = rootProject.file(".tools/mappings")
    val name = "app-${android.defaultConfig.versionCode}"
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
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.46.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.46.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-messaging")
    // X25519 for phone/location/health sharing keys (the platform has it only from API 33).
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    // Installs src/main/baseline-prof.txt on sideloaded installs too (Play does it itself).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.core:core-splashscreen:1.2.0")
    // AllFather look: frosted glass (Haze) and continuous G2 corners (Capsule).
    implementation("dev.chrisbanes.haze:haze:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.1")
    implementation("io.github.kyant0:capsule:2.1.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")
    testImplementation("org.robolectric:robolectric:4.17")
}
