import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "ai.aivonic.secondhand"
  compileSdk = 36

  buildFeatures {
    compose = true
    buildConfig = true
  }

  defaultConfig {
    applicationId = "ai.aivonic.secondhand"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1"
    // Developer Mode in the Meta AI app: attestation is off, so both ids are "0". The device token for the
    // Second Hand server is NOT built in: this repository and its build artifacts are public, so the app
    // asks for the token once and keeps it on the phone.
    manifestPlaceholders["mwdat_application_id"] = "0"
    manifestPlaceholders["mwdat_client_token"] = "0"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.material3)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.exifinterface)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.okhttp)
}
