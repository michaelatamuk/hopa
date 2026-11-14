plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    test {
        // Relative to this module, so any build that includes it finds the
        // repository's contracts (the Android build and the Windows sidecar).
        resources.srcDir(file("../../contracts"))
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.slf4j.nop)
}

tasks.test {
    useJUnit()
}

// core is a pure JVM module: the Android composition root lives in app and the
// platform adapters in capabilities. This is the Gradle half of the rule that
// tools/checks/check-android-structure.py also enforces in CI.
val checkNoAndroidImports by tasks.registering {
    group = "verification"
    description = "Fails when core imports android.* or androidx.*."
    val sources = fileTree("src") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val offenders = sources.files.flatMap { file ->
            file.readLines()
                .withIndex()
                .filter { (_, line) ->
                    val text = line.trimStart()
                    text.startsWith("import android.") || text.startsWith("import androidx.")
                }
                .map { (index, line) ->
                    "${file.relativeTo(projectDir)}:${index + 1}: ${line.trim()}"
                }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "core must not import Android:\n" + offenders.joinToString("\n"),
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkNoAndroidImports)
}
