import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.retrotube.app"
    compileSdk = 36

    // TMDB_API_KEY: local dev reads it from local.properties (gitignored, same
    // file Android Studio already writes sdk.dir into) so it's never committed;
    // CI reads it from a repo secret via env var instead, same split the release
    // keystore already uses. Empty string (not a build failure) if neither is
    // set, so the app still builds for anyone who hasn't set up TMDB yet --
    // TmdbClient itself is responsible for treating a blank key as "unconfigured".
    val localProperties = Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { load(it) }
        }
    }
    val tmdbApiKey = System.getenv("TMDB_API_KEY")
        ?: localProperties.getProperty("TMDB_API_KEY")
        ?: ""

    // Same split as TMDB_API_KEY above -- a per-account OpenSubtitles key, read
    // from local.properties locally / an env var in CI, never committed. Blank
    // if unset; OpenSubtitlesClient treats a blank key as "unconfigured".
    val openSubtitlesApiKey = System.getenv("OPENSUBTITLES_API_KEY")
        ?: localProperties.getProperty("OPENSUBTITLES_API_KEY")
        ?: ""

    defaultConfig {
        applicationId = "com.retrotube.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.0"
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$openSubtitlesApiKey\"")
    }

    // Populated from env vars in CI (see .github/workflows/release.yml) so release
    // builds are signed with a persistent key -- otherwise Gradle falls back to a
    // fresh debug key per machine, which would make every release un-updatable
    // from the last (signature mismatch).
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    val releaseKeystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    val hasReleaseSigningConfig = listOf(
        releaseKeystorePath,
        releaseKeystorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrEmpty() }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")
    implementation("androidx.media3:media3-datasource:$media3Version")

    // SMB2/3 client for the "Add network share" library source.
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    // Encrypts saved SMB credentials at rest, rather than plain SharedPreferences.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Filename-parsing/episode-reconciliation logic is plain Kotlin with no
    // Android dependency specifically so it can run here as a fast JVM unit
    // test against the spec's worked examples, no emulator/device needed.
    testImplementation("junit:junit:4.13.2")
}
