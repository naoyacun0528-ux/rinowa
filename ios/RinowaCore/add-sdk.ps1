# Adds the Windows SDK to the existing Build Tools install.
#
# Swift links against ucrt.lib and kernel32.lib, which come from the Windows SDK rather than
# from MSVC. Without it the build reaches the linker and stops there — further than a
# missing toolchain gets, and looking nothing like a missing SDK.
#
# The Visual Studio installer answers 87 ("invalid parameter") for several different
# mistakes and never says which, so this tries the documented forms in order and reports
# what each one did. Success is checked against the disk, not the exit code.
$ErrorActionPreference = 'Continue'

$kits = 'C:\Program Files (x86)\Windows Kits\10\Lib'
$path = 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools'
$component = 'Microsoft.VisualStudio.Component.Windows11SDK.22621'

function Installed {
    if (-not (Test-Path $kits)) { return $false }
    Get-ChildItem $kits -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'um\arm64') }
}

# 1. setup.exe rather than vs_installer.exe, and without --wait, which the modify verb does
#    not take.
$setup = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer\setup.exe'
if (Test-Path $setup) {
    Write-Host 'trying setup.exe modify'
    $arguments = 'modify --installPath "' + $path + '" --add ' + $component + ' --quiet --norestart'
    $process = Start-Process -FilePath $setup -ArgumentList $arguments -Wait -PassThru
    Write-Host "  exit=$($process.ExitCode)"
    if (Installed) { Write-Host 'installed via setup.exe'; exit 0 }
}

# 2. winget, handing the same arguments to the bootstrapper it downloads.
Write-Host 'trying winget --override'
$override = 'modify --installPath "' + $path + '" --add ' + $component + ' --quiet --norestart'
winget install --id Microsoft.VisualStudio.2022.BuildTools -e --force `
    --accept-source-agreements --accept-package-agreements --override $override 2>&1 |
    Select-Object -Last 4
if (Installed) { Write-Host 'installed via winget'; exit 0 }

Write-Host 'the Windows SDK is still not installed'
exit 1
