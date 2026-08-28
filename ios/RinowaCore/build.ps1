<#
    Windows 上で RinowaCore をビルドして試す。

        powershell -File ios\RinowaCore\build.ps1            # テスト
        powershell -File ios\RinowaCore\build.ps1 -Build     # ビルドのみ

    「swift test」ではなくスクリプトにしている理由。

    Windows の Swift は MSVC でリンクし、`link.exe` は Visual Studio の開発者環境の
    中にしか見つからない。普通のシェルから走らせると、こう言って止まる。

        error: toolchain is invalid: could not find CLI tool `link`

    Swift の入れ方が壊れているように読めるが、そうではない。

    そのシェルが勝手にやってくれないことがあと2つあり、どちらも原因とは別の場所を
    指すエラーになる。

    - `Enter-VsDevShell` は `vswhere.exe` を呼ぶが、あれはツールチェーンではなく
      *インストーラ*に付いてくるもので、既定では PATH に無い。
    - PATH を書き換える。Swift は Visual Studio の一部ではないので、そのために
      用意したシェルから `swift` が消える。

    なのでこのスクリプトが3つとも整えてからビルドする。どこからでも動く。CI の中でも、
    メニューから「Native Tools Command Prompt」を選べない場面でも。

    このパッケージが Windows の機械にある理由。

    形式は実装どうしの契約（docs/WIRE_FORMATS.md）。この半分は Android のテストと
    同じベクタを読むので、食い違いは数か月後に誰かの端末の、送り直せないメッセージの
    上ではなく、ここに出る。SwiftUI も AVFoundation も import しない。あれらは Mac が
    無いと検証できないし、検証できないコードはまだ書く価値が無い。
#>

param(
    [switch]$Build
)

$ErrorActionPreference = 'Stop'

$vs = 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools'
if (-not (Test-Path $vs)) {
    throw "Visual Studio Build Tools not found at $vs. Swift on Windows links with MSVC."
}

$installer = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer'
if (Test-Path $installer) { $env:Path = $installer + ';' + $env:Path }

Import-Module (Join-Path $vs 'Common7\Tools\Microsoft.VisualStudio.DevShell.dll')
# -SkipAutomaticLocation で作業ディレクトリを保つ。付けないとシェルが VS の
# インストール先へ移動し、ベクタへの相対パスが解決できなくなる。
Enter-VsDevShell -VsInstallPath $vs -SkipAutomaticLocation `
    -DevCmdArguments '-arch=arm64 -host_arch=arm64' | Out-Null

# Swift を PATH に戻す。新しい順に探し、版番号を固定しない。
$swiftRoot = Join-Path $env:LOCALAPPDATA 'Programs\Swift'
$swiftBins = @(
    Get-ChildItem (Join-Path $swiftRoot 'Toolchains') -Directory -ErrorAction SilentlyContinue
    Get-ChildItem (Join-Path $swiftRoot 'Runtimes') -Directory -ErrorAction SilentlyContinue
) | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'usr\bin' } |
    Where-Object { Test-Path $_ }

if (-not $swiftBins) { throw "Swift toolchain not found under $swiftRoot" }
$env:Path = ($swiftBins -join ';') + ';' + $env:Path

# インストーラは SDKROOT をユーザー環境変数に置く。インストール前に起動していた
# シェル（あるいは開発者シェルのように環境を作り直したもの）にはそれが無い。
# 無いとコンパイラは「unable to load standard library」と言い、壊れたツールチェーンの
# ように聞こえるが、実際はパスが1つ足りないだけ。
if (-not $env:SDKROOT) {
    $fromUser = [System.Environment]::GetEnvironmentVariable('SDKROOT', 'User')
    if ($fromUser) {
        $env:SDKROOT = $fromUser
    } else {
        $platforms = Get-ChildItem (Join-Path $swiftRoot 'Platforms') -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1
        if (-not $platforms) { throw "no Swift platform SDK under $swiftRoot" }
        $env:SDKROOT = Join-Path $platforms.FullName 'Windows.platform\Developer\SDKs\Windows.sdk'
    }
}

# SwiftPM は依存の取得で git を呼ぶ。git がどこにあるかは機械ごとの事実なので、
# コミットも同梱もしない tools/local.ps1 から読む。git が無いと取得は
# 「Failed to clone」で失敗し、通信の問題のように見えるが、実行ファイルが無いだけ。
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    $local = Join-Path $PSScriptRoot '..\..\tools\local.ps1'
    if (Test-Path $local) { . $local }
    if (-not $env:RINOWA_GIT -or -not (Test-Path $env:RINOWA_GIT)) {
        throw 'git not found; set $env:RINOWA_GIT in tools/local.ps1. SwiftPM needs it to fetch dependencies'
    }
    $env:Path = (Split-Path -Parent $env:RINOWA_GIT) + ';' + $env:Path
}

Set-Location $PSScriptRoot

if ($Build) {
    swift build
} else {
    swift test
}
