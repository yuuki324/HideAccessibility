// AGP 9.x has Kotlin support built in, so `org.jetbrains.kotlin.android` is intentionally
// NOT applied here — AGP 9 fails the build if it is:
//   "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support
//    since AGP 9.0."
plugins {
    alias(libs.plugins.android.application)
}

// Version comes from git instead of being typed by hand, so a release cannot be tagged
// v1.1.0 while the APK still reports 1.0.0:
//
//   versionName = nearest tag ("v1.0.0" -> "1.0.0"); a commit past the tag appends the
//                 describe suffix, e.g. "1.0.0-3-gabc1234"
//   versionCode = number of commits in the branch -- monotonic as long as history is not
//                 rewritten, and always increasing between releases
//
// Both fall back to a fixed value when git is unavailable (source archive, CI checkout
// without tags). Override either with -Phideaccessibility.versionName=... / .versionCode=...
fun gitOutput(vararg args: String): String? = try {
    val process = ProcessBuilder(listOf("git") + args)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
    process.waitFor()
    text.ifBlank { null }
} catch (_: Exception) {
    null
}

val gitVersionName: String = providers.gradleProperty("hideaccessibility.versionName").orNull
    ?: gitOutput("describe", "--tags", "--always")
        ?.removePrefix("v")
        ?.takeIf { it.isNotBlank() }
    ?: "1.0.0"

val gitVersionCode: Int = providers.gradleProperty("hideaccessibility.versionCode").orNull?.toIntOrNull()
    ?: gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()
    ?: 1

android {
    namespace = "app.hide.accessibility"

    // libxposed 102.0.0 declares minCompileSdk=37 in its AAR metadata, so compiling
    // against anything lower fails in :app:checkReleaseAarMetadata.
    compileSdk = 37

    defaultConfig {
        applicationId = "app.hide.accessibility"
        minSdk = 27

        // targetSdk stays at 34 on purpose: it opts the app into new runtime behaviour and
        // affects how it is treated on newer devices. The module only runs inside
        // system_server and its UI is plain framework views, so raising compileSdk alone is
        // enough to satisfy the dependency.
        targetSdk = 34
        versionCode = gitVersionCode
        versionName = gitVersionName

        // API 102 module metadata lives in app/src/main/resources/META-INF/xposed/
        // (java_init.list, module.prop, scope.list) and is packaged as-is.
    }

    // Release signing is deliberately optional and keeps the keystore OUT of the repository:
    // the Android debug key is public (its password is literally "android"), so anyone who
    // has it can publish an update that installs over yours.
    //
    // Local machine (put these three lines in ~/.gradle/gradle.properties, never in git):
    //     hideaccessibility.storeFile=E:/dsh workspace/keys/hideaccessibility-release.jks
    //     hideaccessibility.storePassword=<your password>
    //     hideaccessibility.keyAlias=hideaccessibility
    //
    // With them present, `assembleRelease` produces a properly signed APK. Without them --
    // e.g. on CI, which holds no secrets -- the release build is simply left unsigned, so
    // the task still succeeds for build verification. An unsigned APK cannot be installed;
    // that is intentional, so a test artifact is never mistaken for a release.
    val releaseKeystore = providers.gradleProperty("hideaccessibility.storeFile").orNull
    val releaseKeystoreFile = releaseKeystore?.let { file(it) }
    if (releaseKeystoreFile != null && releaseKeystoreFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = providers.gradleProperty("hideaccessibility.storePassword").orNull
                keyAlias = providers.gradleProperty("hideaccessibility.keyAlias").orNull
                keyPassword = providers.gradleProperty("hideaccessibility.storePassword").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed only when the keystore above is configured.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    // Keep resource shrinking off: the module ships a handful of tiny resources and
    // this project is not distributed through a store pipeline.
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

// No top-level `kotlin { }` block: that extension belongs to the Kotlin Gradle Plugin
// (KGP), and under AGP 9 the project does not apply KGP at all. Kotlin compilation is
// configured through AGP's built-in support instead.

dependencies {
    implementation(libs.libxposed.api)
    // Required only on the app side: it provides XposedServiceHelper and the Binder
    // stubs (IXposedService$Stub). `api` already depends on `interface` at runtime,
    // but declaring it keeps the intent explicit.
    implementation(libs.libxposed.service)
}
