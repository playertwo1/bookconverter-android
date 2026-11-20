plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.ksp)
  alias(libs.plugins.androidx.room)
}

android {
  namespace = "io.github.playertwo1.bookconverter"
  compileSdk = 36

  defaultConfig {
    applicationId = "io.github.playertwo1.bookconverter"
    minSdk = 34
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

// Schema JSON per database version, committed so MigrationTest can compare against it.
room { schemaDirectory("$projectDir/schemas") }

dependencies {
  implementation(project(":core"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.kotlinx.coroutines.core)
  ksp(libs.androidx.room.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.androidx.work.testing)

  androidTestImplementation(libs.androidx.room.testing)
  androidTestImplementation(libs.androidx.work.testing)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
}
