# Auto-build watcher for the Claude AI Assistant plugin.
#
# Run this once per session and leave it open in a terminal. It will:
#   1. Do everything build.ps1 does (find Java, set up Gradle, first build).
#   2. Then run Gradle in --continuous mode, rebuilding plugin.zip every time
#      a source file changes. Press Ctrl+C to stop.
#
# Whoever is editing code (you or Claude) doesn't have to click anything to
# refresh the artifact - it just appears next to the script.

$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot

# Dot-source build.ps1 so its Find-JavaHome / Resolve-GradleCommand functions
# and any JAVA_HOME / PATH adjustments stick in this process for the watcher.
. .\build.ps1

Write-Host ""
Write-Host "=== Watching for source changes - press Ctrl+C to stop ===" -ForegroundColor Cyan
Write-Host "Anything that edits a file under src/ or build.gradle.kts will"
Write-Host "trigger a rebuild. plugin.zip is refreshed automatically."
Write-Host ""

# Continuous mode: Gradle watches inputs declared by the buildPlugin task and
# reruns on change. The doLast hook in build.gradle.kts copies the produced
# zip into plugin.zip after each build.
& .\gradlew.bat "buildPlugin" "--continuous"
