import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Update / distribution settings (local only; nothing is published by building) ----
//
// quietHome.updateRepo   "owner/name" of the PUBLIC GitHub repo whose Releases the app checks.
//                        Empty = updates not configured (Settings shows that). Set it in
//                        ~/.gradle/gradle.properties or gradle.properties when you decide on a repo.
// quietHome.keystore*    Optional dedicated release key (see RELEASING.md). If absent, the release
//                        build is signed with this Mac's debug key, exactly as before.
fun prop(name: String): String? = (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }

val updateRepo = prop("quietHome.updateRepo") ?: ""

/** One APK name for every release, so .../releases/latest/download/QuietHome.apk stays stable. */
val apkAssetName = "QuietHome.apk"
val metadataAssetName = "QuietHome.json"

android {
    namespace = "io.github.gabrielastilla.quiethome"
    compileSdk = 35

    defaultConfig {
        // Override only to build a one-off "bridge" update for an older install (see RELEASING.md › Migration).
        applicationId = prop("quietHome.applicationId") ?: "io.github.gabrielastilla.quiethome"
        minSdk = 26
        targetSdk = 35
        // versionCode must increase for every release; the updater compares it numerically.
        versionCode = prop("quietHome.versionCode")?.toInt() ?: 4   // 3 is reserved for the old-package bridge build
        versionName = prop("quietHome.versionName") ?: "1.2.0"
        prop("quietHome.appIdSuffix")?.let { applicationIdSuffix = it } // test builds only
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "UPDATE_APK_ASSET", "\"$apkAssetName\"")
        buildConfigField("String", "UPDATE_METADATA_ASSET", "\"$metadataAssetName\"")
        buildConfigField("String", "UPDATE_API_BASE", "\"https://api.github.com\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        val ks = prop("quietHome.keystore")
        if (ks != null && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = prop("quietHome.keystorePassword")
                keyAlias = prop("quietHome.keyAlias")
                keyPassword = prop("quietHome.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Dedicated release key if configured, otherwise the debug key (as in earlier builds).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // A single universal APK (no native code, no ABI/density splits): one file works on every Google TV.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * Packages a release for GitHub: copies the universal release APK to
 *   app/build/release/QuietHome.apk
 * and writes its metadata next to it:
 *   app/build/release/QuietHome.json   {packageName, versionCode, versionName, apk, sha256, minSdk}
 * Upload both files to the GitHub release (see RELEASING.md). Nothing is uploaded by this task.
 */
tasks.register("releaseAssets") {
    dependsOn("assembleRelease")
    doLast {
        val src = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        val dir = layout.buildDirectory.dir("release").get().asFile.apply { mkdirs() }
        val apk = File(dir, apkAssetName)
        src.copyTo(apk, overwrite = true)
        val sha = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        val json = """
            {
              "packageName": "${android.defaultConfig.applicationId}${prop("quietHome.appIdSuffix") ?: ""}",
              "versionCode": ${android.defaultConfig.versionCode},
              "versionName": "${android.defaultConfig.versionName}",
              "apk": "$apkAssetName",
              "sha256": "$sha",
              "minSdk": ${android.defaultConfig.minSdk}
            }
        """.trimIndent()
        File(dir, metadataAssetName).writeText(json + "\n")
        println("Release assets: ${apk.path} (sha256 $sha) and ${File(dir, metadataAssetName).path}")
    }
}

dependencies {
    // Two small AndroidX libraries. No Compose, no AppCompat, no Leanback.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Physics-based springs for focus scaling (retargets smoothly on rapid input).
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (Android's copy is a stub off-device).
    testImplementation("org.json:json:20240303")
}
