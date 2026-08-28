# One-off: what the developer shell actually sets, and what is on disk.
$ErrorActionPreference = 'Stop'

$vs = 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools'
$installer = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer'
if (Test-Path $installer) { $env:Path = $installer + ';' + $env:Path }

Import-Module (Join-Path $vs 'Common7\Tools\Microsoft.VisualStudio.DevShell.dll')
Enter-VsDevShell -VsInstallPath $vs -SkipAutomaticLocation `
    -DevCmdArguments '-arch=arm64 -host_arch=arm64' | Out-Null

"=== LIB ==="
($env:LIB -split ';') | ForEach-Object { $_ }

"=== kits on disk ==="
Get-ChildItem 'C:\Program Files (x86)\Windows Kits\10\Lib' -Directory -ErrorAction SilentlyContinue |
    ForEach-Object {
        $arm = Join-Path $_.FullName 'um\arm64'
        "$($_.Name)  um/arm64: $(Test-Path $arm)"
    }
