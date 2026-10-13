plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.hide.accessibility"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.hide.accessibility"
        minSdk = 27
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.libxposed.api)
    // Required only on the app side: it provides XposedServiceHelper and the Binder
    // stubs (IXposedService$Stub). `api` already depends on `interface` at runtime,
    // but declaring it keeps the intent explicit.
    implementation(libs.libxposed.service)
}
