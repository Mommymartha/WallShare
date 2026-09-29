// Top-level build file. No module-specific config lives here —
// plugins are declared with `apply false` here and actually applied per-module.
// google-services is declared but not applied anywhere yet (see app/build.gradle.kts
// for why) - this line alone does not require google-services.json.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.google.services) apply false
//    id("com.google.gms.google-services") version "4.4.2" apply false
}
