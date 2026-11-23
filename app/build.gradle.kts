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
    versionCode = 5
    versionName = "1.0.3"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }

  signingConfigs {
    create("release") {
      val ksPath = System.getenv("KEYSTORE_PATH")
      if (!ksPath.isNullOrBlank()) {
        storeFile = file(ksPath)
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      val ksPath = System.getenv("KEYSTORE_PATH")
      if (!ksPath.isNullOrBlank()) {
        signingConfig = signingConfigs.getByName("release")
      }
      isMinifyEnabled = false
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
