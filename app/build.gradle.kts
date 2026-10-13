// AGP 9.x has Kotlin support built in, so `org.jetbrains.kotlin.android` is intentionally
// NOT applied here — AGP 9 fails the build if it is:
//   "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support
//    since AGP 9.0."
plugins {
    alias(libs.plugins.android.application)
}

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
        versionCode = 1
        versionName = "1.0.0"

        // API 102 module metadata lives in app/src/main/resources/META-INF/xposed/
        // (java_init.list, module.prop, scope.list) and is packaged as-is.
    }

    signingConfigs {
        // The release build is signed with the debug key so the artifact is installable
        // out of the box. Replace with your own keystore before distributing widely.
        create("release") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
