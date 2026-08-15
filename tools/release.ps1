<#
    Builds a release drop into outputs\<version>\.

    Produces:
      echo-<version>-debug.apk
      echo-<version>-source.zip
    The release notes (RELEASE-<version>.md) are written by hand alongside them.

    Usage, from anywhere:
      powershell -File C:\dev\echo\tools\release.ps1

    The version is read from app\build.gradle.kts so there is one place to change it.
#>

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$android = Join-Path $repo 'android'
$gradleFile = Join-Path $android 'app\build.gradle.kts'

$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot'
$env:ANDROID_HOME = 'C:\Android\Sdk'

# ---- version ---------------------------------------------------------------
$match = Select-String -Path $gradleFile -Pattern 'versionName\s*=\s*"([^"]+)"'
if (-not $match) { throw "versionName not found in $gradleFile" }
$version = $match.Matches[0].Groups[1].Value
Write-Host "version: $version"

$outDir = Join-Path $repo "outputs\$version"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# ---- build (no device needed) ----------------------------------------------
Write-Host 'building...'
& (Join-Path $android 'gradlew.bat') -p $android assembleDebug | Out-Null
if ($LASTEXITCODE -ne 0) { throw "gradle assembleDebug failed ($LASTEXITCODE)" }

$apk = Join-Path $android 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "APK not found at $apk" }
$apkOut = Join-Path $outDir "echo-$version-debug.apk"
Copy-Item $apk $apkOut -Force
Write-Host "apk    : $apkOut  ($([math]::Round((Get-Item $apkOut).Length / 1MB, 2)) MB)"

# ---- source zip ------------------------------------------------------------
# Staged into a temp tree first so the archive has no build output, no git history,
# no machine-specific local.properties, and does not contain outputs\ recursively.
$staging = Join-Path $env:TEMP "echo-src-$version"
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Force -Path $staging | Out-Null

$excludeDirs = @('.git', '.gradle', 'build', 'outputs', '.idea', '.cxx')
Get-ChildItem -Path $repo -Recurse -File | Where-Object {
    $relative = $_.FullName.Substring($repo.Length).TrimStart('\')
    $parts = $relative -split '\\'
    $blocked = $false
    foreach ($part in $parts) { if ($excludeDirs -contains $part) { $blocked = $true } }
    if ($_.Name -eq 'local.properties') { $blocked = $true }
    -not $blocked
} | ForEach-Object {
    $relative = $_.FullName.Substring($repo.Length).TrimStart('\')
    $target = Join-Path $staging $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Copy-Item $_.FullName $target -Force
}

$zipOut = Join-Path $outDir "echo-$version-source.zip"
if (Test-Path $zipOut) { Remove-Item $zipOut -Force }
Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zipOut
Remove-Item $staging -Recurse -Force
Write-Host "source : $zipOut  ($([math]::Round((Get-Item $zipOut).Length / 1KB, 0)) KB)"

Write-Host ''
Write-Host "done -> $outDir"
Get-ChildItem $outDir | Select-Object Name, @{n = 'Size'; e = { '{0:N0} KB' -f ($_.Length / 1KB) } }
