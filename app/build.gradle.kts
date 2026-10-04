import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
val cloudSettings = Properties().apply {
    val config = rootProject.file(".poodles.properties")
    if (config.exists()) config.inputStream().use { load(it) }
}
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""
android {
    namespace = "com.mrpoodles.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mrpoodles.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 7
        versionName = "0.4.0"
        buildConfigField("String", "BACKEND_URL", quoted(cloudSettings.getProperty("backend.url", "")))
        buildConfigField("String", "APP_ACCESS_TOKEN", quoted(cloudSettings.getProperty("app.token", "")))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!models:!*.gguf:!tessdata:!*.traineddata" }
    packaging { jniLibs { useLegacyPackaging = false } }
    buildTypes { release { isMinifyEnabled = false } }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val verifyBundledAssets by tasks.registering {
    doLast {
        val assets = file("src/main/assets")
        listOf("nutrition.json", "NOTICES.txt").forEach {
            check(assets.resolve(it).length() > 0) { "Missing bundled asset: $it" }
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(verifyBundledAssets) }
