// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

// Automatically restore debug.keystore from debug.keystore.base64 if missing (e.g. in CI / GitHub Actions)
val debugKeystoreFile = rootProject.file("debug.keystore")
val debugKeystoreBase64File = rootProject.file("debug.keystore.base64")
if (!debugKeystoreFile.exists() && debugKeystoreBase64File.exists()) {
  try {
    val decoded = java.util.Base64.getMimeDecoder().decode(debugKeystoreBase64File.readText().trim())
    debugKeystoreFile.writeBytes(decoded)
  } catch (_: Exception) {}
}
