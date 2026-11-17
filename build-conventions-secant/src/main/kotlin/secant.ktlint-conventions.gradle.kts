plugins {
    id("java")
}

val ktlint by configurations.creating

dependencies {
    ktlint("com.pinterest.ktlint:ktlint-cli:${project.property("KTLINT_VERSION")}") {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named<Bundling>(Bundling.EXTERNAL))
        }
    }
}

tasks {
    val editorConfigFile = rootProject.file("tools/.editorconfig")
    val baselineFile = rootProject.file("tools/ktlint-baseline.xml")

    // `-PktlintFiles=a.kt,b.kt` narrows a run to specific paths; without it the whole tree is checked.
    val filePatterns: List<String> =
        (project.findProperty("ktlintFiles") as String?)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("**/src/**/*.kt", "!**/build/**.kt")
    val ktlintArgs = filePatterns + listOf("--editorconfig=$editorConfigFile")

    register("ktlint", org.gradle.api.tasks.JavaExec::class) {
        description = "Check code style with ktlint"
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        // The repo carries ~6k pre-existing violations, which made this task fail unconditionally and
        // therefore tell us nothing about NEW code. The baseline grandfathers exactly those findings, so
        // the task passes today and fails the moment a fresh violation appears. Regenerate deliberately
        // (delete the file and re-run) — never to silence a violation you just introduced.
        args = ktlintArgs + listOf("--baseline=$baselineFile")
    }

    register("ktlintFormat", org.gradle.api.tasks.JavaExec::class) {
        // https://github.com/pinterest/ktlint/issues/1195#issuecomment-1009027802
        jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")

        description = "Apply code style formatting with ktlint"
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        // No baseline here on purpose: formatting must be free to fix anything in the requested scope.
        args = listOf("-F") + ktlintArgs
    }
}

java {
    val javaVersion = JavaVersion.toVersion(project.property("ANDROID_JVM_TARGET").toString())
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}