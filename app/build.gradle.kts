plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Číslo verze dodává GitHub Actions (číslo buildu), lokálně 1.
val buildNumber = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "cz.gramantik.meteowidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.gramantik.meteowidget"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    signingConfigs {
        create("release") {
            // Pevný klíč, aby šly nové verze instalovat přes starou.
            storeFile = rootProject.file("signing/release.jks")
            storePassword = "zdXU8odgB3WAoW581HE9YJPddomiIXUs"
            keyAlias = "meteowidget"
            keyPassword = "zdXU8odgB3WAoW581HE9YJPddomiIXUs"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.work:work-runtime:2.9.1")
}
