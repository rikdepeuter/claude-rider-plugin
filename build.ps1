# Build the Claude Chat for Rider plugin and drop the result as .\plugin.zip.
#
# Requires JDK 21+ on PATH (or JAVA_HOME). If neither gradlew.bat nor a
# system 'gradle' is found, this script downloads Gradle 8.10 once into
# %LOCALAPPDATA%\claude-chat-rider\ and uses it. No admin, no winget needed.

# Keep this on "Continue" so writes to stderr from native commands (java,
# gradle) don't get promoted to terminating errors. We add -ErrorAction Stop
# explicitly to the cmdlets we want try/catch to actually catch.
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot

$GradleVersion = "8.10"

function Write-Section($text) {
    Write-Host ""
    Write-Host ("=== " + $text + " ===") -ForegroundColor Cyan
}

function Fail($message) {
    Write-Host ""
    Write-Host ("[x] " + $message) -ForegroundColor Red
    exit 1
}

Write-Section "Claude Chat for Rider - build"

# ---- Java check ---------------------------------------------------------
# Don't trust PATH alone - winget often installs a JDK without refreshing
# PATH in the current terminal. Hunt for a java.exe ourselves, set JAVA_HOME,
# and prepend its bin to PATH for the rest of this process.

function Find-JavaHome {
    # 1. Respect an explicit JAVA_HOME if it points at something real.
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }

    # 2. java already on PATH? Trust it.
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) {
        $resolvedHome = Split-Path -Parent (Split-Path -Parent $onPath.Source)
        return $resolvedHome
    }

    # 3. Walk well-known install roots and pick the highest jdk-* directory.
    $roots = @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Java",
        "C:\Program Files\OpenJDK",
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files\Zulu",
        "C:\Program Files\BellSoft\LibericaJDK-21",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
    )
    $candidates = @()
    foreach ($r in $roots) {
        if (Test-Path $r) {
            $candidates += Get-ChildItem -Path $r -Directory -ErrorAction SilentlyContinue |
                Where-Object { Test-Path (Join-Path $_.FullName "bin\java.exe") }
        }
    }

    # 4. Rider / IntelliJ ship a JBR. Use it as a last resort.
    $jbrRoots = @(
        "C:\Program Files\JetBrains",
        "$env:LOCALAPPDATA\Programs\JetBrains",
        "$env:LOCALAPPDATA\JetBrains\Toolbox\apps"
    )
    foreach ($r in $jbrRoots) {
        if (Test-Path $r) {
            $candidates += Get-ChildItem -Path $r -Directory -Recurse -Depth 4 -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -eq "jbr" -and (Test-Path (Join-Path $_.FullName "bin\java.exe")) }
        }
    }

    if ($candidates.Count -eq 0) { return $null }

    # Prefer names containing "21", then "22"+, then anything else; tie-break on newest.
    $scored = $candidates | ForEach-Object {
        $name = $_.Name.ToLower()
        $score = 0
        if ($name -match "21") { $score = 100 }
        elseif ($name -match "22|23|24|25") { $score = 90 }
        elseif ($name -match "20|19|18|17") { $score = 50 }
        elseif ($name -eq "jbr") { $score = 80 }   # Rider's JBR is recent
        [pscustomobject]@{ Path = $_.FullName; Score = $score; LastWrite = $_.LastWriteTime }
    }
    $best = $scored | Sort-Object Score, LastWrite -Descending | Select-Object -First 1
    return $best.Path
}

$javaHome = Find-JavaHome
if (-not $javaHome) {
    Fail @"
No Java found. Install JDK 21:
  winget install EclipseAdoptium.Temurin.21.JDK
Then OPEN A NEW TERMINAL (or reboot) so PATH is refreshed, and re-run build.bat.
If Java is installed somewhere unusual, set JAVA_HOME and re-run.
"@
}

$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome "bin") + ";" + $env:Path
$javaOut = & java -version 2>&1
Write-Host ("Java: " + ($javaOut | Select-Object -First 1))
Write-Host ("JAVA_HOME (this run): " + $javaHome)

# ---- Locate a Gradle to run ---------------------------------------------
function Resolve-GradleCommand {
    if (Test-Path ".\gradlew.bat") {
        return @{ Path = (Resolve-Path ".\gradlew.bat").Path; Source = "wrapper" }
    }
    $systemGradle = Get-Command gradle -ErrorAction SilentlyContinue
    if ($systemGradle) {
        return @{ Path = $systemGradle.Source; Source = "system" }
    }

    # No wrapper, no system gradle - fetch a Gradle distribution into our cache.
    $cacheRoot = Join-Path $env:LOCALAPPDATA "claude-chat-rider"
    $gradleHome = Join-Path $cacheRoot ("gradle-" + $GradleVersion)
    $gradleBin = Join-Path $gradleHome "bin\gradle.bat"
    if (-not (Test-Path $gradleBin)) {
        New-Item -ItemType Directory -Force -Path $cacheRoot | Out-Null
        $zipPath = Join-Path $cacheRoot ("gradle-" + $GradleVersion + "-bin.zip")
        $url = "https://services.gradle.org/distributions/gradle-" + $GradleVersion + "-bin.zip"
        Write-Host ("Gradle not found locally - downloading " + $url) -ForegroundColor Yellow
        Write-Host ("  (~150 MB, one-time, cached under " + $cacheRoot + ")") -ForegroundColor DarkGray
        try {
            # TLS 1.2 fix for older PowerShell sessions
            [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zipPath -ErrorAction Stop
        } catch {
            Fail ("Failed to download Gradle: " + $_.Exception.Message)
        }
        Write-Host "Extracting..." -ForegroundColor Yellow
        Expand-Archive -Path $zipPath -DestinationPath $cacheRoot -Force -ErrorAction Stop
        Remove-Item $zipPath -ErrorAction SilentlyContinue
        if (-not (Test-Path $gradleBin)) {
            Fail ("Extracted Gradle but " + $gradleBin + " is missing.")
        }
    }
    return @{ Path = $gradleBin; Source = "downloaded" }
}

$gradle = Resolve-GradleCommand
Write-Host ("Using Gradle (" + $gradle.Source + "): " + $gradle.Path)

# ---- Generate the wrapper if it isn't there (so repeat builds are fast) -
if (-not (Test-Path ".\gradlew.bat")) {
    Write-Section "Generating Gradle wrapper"
    & $gradle.Path "wrapper" "--gradle-version" $GradleVersion
    if ($LASTEXITCODE -ne 0) { Fail ("gradle wrapper failed (exit " + $LASTEXITCODE + ").") }
    if (Test-Path ".\gradlew.bat") {
        $gradle = @{ Path = (Resolve-Path ".\gradlew.bat").Path; Source = "wrapper" }
    }
}

# ---- Build --------------------------------------------------------------
Write-Section "Running gradle buildPlugin"
& $gradle.Path "buildPlugin"
if ($LASTEXITCODE -ne 0) { Fail ("Build failed (exit " + $LASTEXITCODE + "). See output above.") }

# ---- Copy the result to plugin.zip --------------------------------------
$built = Get-ChildItem ".\build\distributions\*.zip" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $built) {
    Fail "Build succeeded but no .zip was produced in build\distributions\."
}
Copy-Item $built.FullName ".\plugin.zip" -Force

Write-Section "Done"
Write-Host ("Built: " + (Resolve-Path .\plugin.zip).Path) -ForegroundColor Green
Write-Host "Install in Rider: Settings -> Plugins -> gear icon -> Install Plugin from Disk..."
