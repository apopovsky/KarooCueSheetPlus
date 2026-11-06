import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.karoocuesheetplus"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.karoocuesheetplus"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
        base.archivesName.set("KarooCueSheetPlus-v${versionName}")
    }

    val keystoreProperties = Properties()
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(keystoreProperties::load)
    }
    val releaseKeystore = providers.environmentVariable("RELEASE_STORE_FILE").orNull
        ?: keystoreProperties.getProperty("storeFile")
    val releaseStorePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
        ?: keystoreProperties.getProperty("storePassword")
    val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
        ?: keystoreProperties.getProperty("keyAlias")
    val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
        ?: keystoreProperties.getProperty("keyPassword")
    val releaseSigningValues = listOf(releaseKeystore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    require(releaseSigningValues.all { it.isNullOrBlank() } || releaseSigningValues.all { !it.isNullOrBlank() }) {
        "Release signing requires storeFile, storePassword, keyAlias and keyPassword via keystore.properties or RELEASE_* environment variables"
    }
    if (!releaseKeystore.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseKeystore)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            if (!releaseKeystore.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
