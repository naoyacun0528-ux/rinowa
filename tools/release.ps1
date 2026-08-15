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
Write-Host "debug  : $apkOut  ($([math]::Round((Get-Item $apkOut).Length / 1MB, 2)) MB)"

# Release build, only when it can actually succeed. Two things must be in place:
# a signing key (otherwise the APK is unsigned and uninstallable) and a Firebase
# registration for the release applicationId (otherwise the Google Services plugin
# fails the build outright).
$hasKeystore = Test-Path (Join-Path $android 'keystore.properties')
$hasReleaseFirebase = (Test-Path (Join-Path $android 'app\src\release\google-services.json')) -or
                      (Test-Path (Join-Path $android 'app\google-services.json'))

if (-not $hasReleaseFirebase) {
    Write-Host 'release: skipped -- blog.nextlab.echo is not registered in Firebase yet.'
    Write-Host '         Add it in the console, download google-services.json again, and'
    Write-Host '         place it at android/app/google-services.json (it covers both variants).'
}

if ($hasKeystore -and $hasReleaseFirebase) {
    & (Join-Path $android 'gradlew.bat') -p $android assembleRelease | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "gradle assembleRelease failed ($LASTEXITCODE)" }

    $rel = Join-Path $android 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path $rel)) { throw "release APK not found at $rel" }
    $relOut = Join-Path $outDir "echo-$version-release.apk"
    Copy-Item $rel $relOut -Force
    Write-Host "release: $relOut  ($([math]::Round((Get-Item $relOut).Length / 1MB, 2)) MB)"
} elseif (-not $hasKeystore) {
    Write-Host 'release: skipped (no keystore.properties on this machine)'
}

# ---- source zip ------------------------------------------------------------
# Staged into a temp tree first so the archive has no build output, no git history,
# no machine-specific local.properties, and does not contain outputs\ recursively.
$staging = Join-Path $env:TEMP "echo-src-$version"
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Force -Path $staging | Out-Null

$excludeDirs = @('.git', '.gradle', 'build', 'outputs', '.idea', '.cxx')

# Anything matching these must never reach a zip that leaves this machine.
# Checked again after the archive is built — see the guard below.
$secretPattern = '(^|\\)(keystore\.properties|local\.properties|google-services\.json)$|\.jks$|\.keystore$'

Get-ChildItem -Path $repo -Recurse -File | Where-Object {
    $relative = $_.FullName.Substring($repo.Length).TrimStart('\')
    $parts = $relative -split '\\'
    $blocked = $false
    foreach ($part in $parts) { if ($excludeDirs -contains $part) { $blocked = $true } }
    if ($relative -match $secretPattern) { $blocked = $true }
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

# Fail-safe. The exclusion list above is a filter someone has to remember to update; this
# inspects the finished archive instead, so a newly added secret cannot slip through
# unnoticed. A leaked signing password cannot be un-shared, so the archive is destroyed
# rather than merely reported.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($zipOut)
$leaked = $archive.Entries | Where-Object { $_.FullName -match $secretPattern } |
    Select-Object -ExpandProperty FullName
$archive.Dispose()
if ($leaked) {
    Remove-Item $zipOut -Force
    throw "secrets found in the source archive, archive destroyed: $($leaked -join ', ')"
}

Write-Host "source : $zipOut  ($([math]::Round((Get-Item $zipOut).Length / 1KB, 0)) KB)  [secret scan clean]"

Write-Host ''
Write-Host "done -> $outDir"
Get-ChildItem $outDir | Select-Object Name, @{n = 'Size'; e = { '{0:N0} KB' -f ($_.Length / 1KB) } }
