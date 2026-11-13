pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "InternalAudioRecorder"
include(":app")

// Fail fast with an actionable message if the checkout is incomplete.
// Without this, a missing app module produces only the cryptic
// "Task 'assembleDebug' not found" error on CI.
val repoRoot = rootDir
val requiredFiles = listOf(
    "build.gradle.kts",
    "gradle/libs.versions.toml",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml"
)
val missing = requiredFiles.filterNot { repoRoot.resolve(it).isFile }
val inSubfolder = repoRoot.resolve("InternalAudioRecorder/settings.gradle.kts").isFile
if (missing.isNotEmpty()) {
    throw GradleException(
        """
        |Project files are missing from this checkout:
        |  ${missing.joinToString("\n  ")}
        |
        |This usually means one of the following:
        |  1. The files were not committed/pushed to the repository root
        |     (most often app/ or app/build.gradle.kts was skipped during upload).
        |  2. The zip archive was extracted with its inner "InternalAudioRecorder"
        |     folder pushed as a subfolder instead of pushing its CONTENTS
        |     to the repository root.
        |
        |Expected layout at the repository root:
        |  settings.gradle.kts / build.gradle.kts / gradle/
        |  app/build.gradle.kts
        |  app/src/main/...
        """.trimMargin()
    )
}
if (inSubfolder) {
    throw GradleException(
        "Project files found under InternalAudioRecorder/ subfolder. " +
            "Push the CONTENTS of that folder to the repository root."
    )
}
