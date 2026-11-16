// Pure Kotlin/JVM: EPUB and provider code is tested on the PC and reused by :app.
plugins {
  alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
  implementation(libs.jsoup)
  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.anthropic.java)
  testImplementation(libs.junit)
  testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
  // Real books from the desktop repo; provider keys come from the environment.
  systemProperty("books.dir", rootProject.file("core/src/test/resources/books").absolutePath)
  testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
