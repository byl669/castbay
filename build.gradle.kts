plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself (no org.jetbrains.kotlin.android); this plugin sets the
    // Kotlin and Compose compiler version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
