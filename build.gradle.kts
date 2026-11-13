// Top-level build file.
// NOTE: AGP 9.x is used together with the Kotlin Android plugin 2.4.21 by setting
// android.builtInKotlin=false / android.newDsl=false in gradle.properties.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
