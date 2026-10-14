import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val propsFile = rootProject.file("gradle.properties")
val props = Properties().apply {
    if (propsFile.exists()) load(propsFile.inputStream())
}
var versionCodeVal = props.getProperty("VERSION_CODE", "1").toInt()
if (gradle.startParameter.taskNames.any { it.contains("assemble", true) || it.contains("bundle", true) }) {
    versionCodeVal++
    props.setProperty("VERSION_CODE", versionCodeVal.toString())
    props.store(propsFile.writer(), null)
}

android {
    namespace = "com.semiletov.telegattsreader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.semiletov.telegattsreader"
        minSdk = 26
        targetSdk = 37
        versionCode = versionCodeVal
        versionName = "1.0.$versionCodeVal"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // JSoup для парсинга HTML
    implementation("org.jsoup:jsoup:1.17.2")

    // Корутины для фоновой работы
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}