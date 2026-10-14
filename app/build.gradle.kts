plugins {
    alias(libs.plugins.android.application)
}

val appVersionName = "1.4.0"

android {
    namespace = "com.kooo.hey"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kooo.hey"
        minSdk = 28
        targetSdk = 36
        versionCode = 29
        versionName = appVersionName

    }

    signingConfigs {
        create("platform") {
            storeFile = file(
                providers.gradleProperty("HEY_PLATFORM_KEYSTORE")
                    .orElse("../keystore/platform.p12")
                    .get()
            )
            storeType = "PKCS12"
            storePassword = providers.gradleProperty("HEY_PLATFORM_STORE_PASSWORD")
                .orElse("android")
                .get()
            keyAlias = providers.gradleProperty("HEY_PLATFORM_KEY_ALIAS")
                .orElse("platform")
                .get()
            keyPassword = providers.gradleProperty("HEY_PLATFORM_KEY_PASSWORD")
                .orElse("android")
                .get()
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("platform")
        }
        release {
            signingConfig = signingConfigs.getByName("platform")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

}

tasks.register<Copy>("copyReleaseApks") {
    group = "build"
    description = "Copies the unified release APK with a distribution-friendly name."

    into(layout.buildDirectory.dir("outputs/release-distribution"))
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "Hey-v$appVersionName-release.apk" }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("copyReleaseApks")
}
