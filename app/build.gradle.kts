import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is optional: either keystore.properties at the repo root
// (local builds) or RELEASE_* environment variables (CI). Without it the
// release APK is simply unsigned instead of failing the build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(property: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    rootProject.file(releaseStoreFile).exists()

android {
    namespace = "io.github.backtraxe.anysave"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.backtraxe.anysave"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.13.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // Lets debug builds point the Telegram client at a local mock server.
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val appVersionName: String = android.defaultConfig.versionName ?: "0.0.0"
val appVersionCode: Int = android.defaultConfig.versionCode ?: 1

// Used by CI to name artifacts/APKs as AnySave-<version>.
tasks.register("printVersionName") {
    group = "help"
    description = "Prints the application versionName."
    doLast { println(appVersionName) }
}

tasks.register("printVersionCode") {
    group = "help"
    description = "Prints the application versionCode."
    doLast { println(appVersionCode) }
}

// Local/CI convenience: dist/AnySave-<version>.apk (or -unsigned.apk).
tasks.register<Copy>("namedReleaseApk") {
    group = "distribution"
    description = "Builds and copies the release APK to dist/AnySave-<version>.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(rootProject.file("dist"))
    rename { originalName ->
        val suffix = if (originalName.contains("unsigned")) "-unsigned" else ""
        "AnySave-$appVersionName$suffix.apk"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Dedicated YouTube extractor (roadmap M6 fallback). GPL-3.0 licensed.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM unit-test classpath (android.jar only stubs it).
    testImplementation("org.json:json:20240303")
}
