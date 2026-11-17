plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
}

// ktlint lives here, not in packages/core, because the Windows sidecar also
// includes that module and does not have the plugin. :core is the first module
// held to zero style warnings; the rest follow module by module.
subprojects {
    if (name == "core") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            filter { exclude("**/catalog/AppCatalog.kt") }
        }
    }
}
