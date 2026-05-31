plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Read API key from local.properties (not committed)
val mobileApiKey: String = run {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.readLines()
            .firstOrNull { it.startsWith("MOBILE_API_KEY=") }
            ?.substringAfter("MOBILE_API_KEY=")
            ?.trim() ?: ""
    } else ""
}

android {
    namespace = "com.davidpurkiss.videoeditor"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.davidpurkiss.videoeditor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "MOBILE_API_KEY", "\"${mobileApiKey}\"")
    }

    buildTypes {
        release {
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.9.0")
}
