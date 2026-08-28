<#
    outputs\<version>\ に成果物一式を作る。

    出るもの:
      rinowa-<version>-debug.apk
      rinowa-<version>-release.apk
      rinowa-<version>-source.zip
    リリースノート（RELEASE-<version>.md）は隣に手で書く。

    どこからでも:
      powershell -File C:\dev\echo\tools\release.ps1

    版は app\build.gradle.kts から読む。直す場所を1つにするため。
#>

param(
    # ソース書庫だけを作る。
    #
    # 過去に壊れたのは箱詰めの部分（デバッグログと、会話の断片がファイル名になった
    # 空ファイル2つが zip に入って出た）。その確認に Gradle のフルビルドを払わせない。
    # 10分かかる確認は、誰もやらない確認になる。
    [switch]$SourceOnly
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$android = Join-Path $repo 'android'
$gradleFile = Join-Path $android 'app\build.gradle.kts'

$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot'
$env:ANDROID_HOME = 'C:\Android\Sdk'

# ---- 版 --------------------------------------------------------------------
$match = Select-String -Path $gradleFile -Pattern 'versionName\s*=\s*"([^"]+)"'
if (-not $match) { throw "versionName not found in $gradleFile" }
$version = $match.Matches[0].Groups[1].Value
Write-Host "version: $version"

$outDir = Join-Path $repo "outputs\$version"

# ---- STATE.md ---------------------------------------------------------------
# 版は1箇所にあり、そこから写す。
#
# 以前は docs/STATE.md に手で書いていて、ずれた。「現在 0.10.0」と「0.12.4 公開済み」と
# 「通話履歴は 0.14.0 で完了」が同じファイルに並び、実際のビルドは 0.17.1 だった。
# あのファイルは、あとから引き継ぐ人が現在地を知るためにある。そこの版が違うのは、
# ファイルが無いより悪い（自信たっぷりに間違っている）。
$stateFile = Join-Path $repo "docs\STATE.md"
if (Test-Path $stateFile) {
    # ここは ASCII だけ。意図的。
    #
    # Windows PowerShell 5.1 は BOM の無い .ps1 をシステムのコードページとして読むので、
    # ここに日本語を書くと文字化けしたまま文書に書き込まれる。前後の言葉は STATE.md 側に
    # あって安全で、ここから来るのは版と日付だけ。
    $stamped = "**$version - " + (Get-Date -Format "yyyy-MM-dd") + "**"
    $state = Get-Content $stateFile -Raw -Encoding UTF8
    $pattern = "(?s)(<!-- stamp:version -->).*?(<!-- /stamp:version -->)"
    if ($state -notmatch $pattern) { throw "docs/STATE.md has no stamp:version block" }
    $replacement = '$1' + "`n" + $stamped + "`n" + '$2'
    $state = [regex]::Replace($state, $pattern, $replacement)
    # BOM 無しで書く。Set-Content -Encoding UTF8 は 5.1 では BOM を付け、Markdown の
    # BOM は最初の見出しの頭に余計な文字として出る。
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($stateFile, $state, $utf8)
    Write-Host "stamped : docs/STATE.md -> $version"
}
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

if ($SourceOnly) {
    Write-Host 'skipping the build (-SourceOnly)'
} else {

# ---- ビルド（端末は要らない）-------------------------------------------------
Write-Host 'building...'
& (Join-Path $android 'gradlew.bat') -p $android assembleDebug | Out-Null
if ($LASTEXITCODE -ne 0) { throw "gradle assembleDebug failed ($LASTEXITCODE)" }

# ABI 分割が debug 側にも効くので app-debug.apk はもう出ない。
# 手元の3機種はすべて arm64。
$apk = Join-Path $android 'app\build\outputs\apk\debug\app-arm64-v8a-debug.apk'
if (-not (Test-Path $apk)) { throw "APK not found at $apk" }
$apkOut = Join-Path $outDir "rinowa-$version-debug.apk"
Copy-Item $apk $apkOut -Force
Write-Host "debug  : $apkOut  ($([math]::Round((Get-Item $apkOut).Length / 1MB, 2)) MB)"

# release ビルドは、本当に成功できるときだけ。2つ揃っている必要がある。署名鍵
# （無いと未署名でインストールできない）と、release の applicationId が Firebase に
# 登録されていること（無いと Google Services プラグインがビルドごと落とす）。
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

    # ABI ごとに分かれている。配るのは arm64 版。
    #
    # universal APK は4アーキテクチャぶんのネイティブを全部抱えており、E2EE を入れた
    # 時点で 120 MB になった。URL で配る以上それを落とすのは実際の利用者なので、
    # 端末が使う1つぶんだけを配る。universal は「何にでも入る版」として outputs に
    # 残すが、latest には出さない。
    $relDir = Join-Path $android 'app\build\outputs\apk\release'
    $rel = Join-Path $relDir 'app-arm64-v8a-release.apk'
    if (-not (Test-Path $rel)) { throw "release APK not found at $rel" }
    $relOut = Join-Path $outDir "rinowa-$version-release.apk"
    Copy-Item $rel $relOut -Force
    Write-Host "release: $relOut  ($([math]::Round((Get-Item $relOut).Length / 1MB, 2)) MB)  [arm64-v8a]"

    $uni = Join-Path $relDir 'app-universal-release.apk'
    if (Test-Path $uni) {
        $uniOut = Join-Path $outDir "rinowa-$version-release-universal.apk"
        Copy-Item $uni $uniOut -Force
        Write-Host "universal: $uniOut  ($([math]::Round((Get-Item $uniOut).Length / 1MB, 2)) MB)"
    }
} elseif (-not $hasKeystore) {
    Write-Host 'release: skipped (no keystore.properties on this machine)'
}

}

# ---- ソース zip -------------------------------------------------------------
# 先に一時ディレクトリへ並べる。書庫にビルド出力も git の履歴も、この機械固有のものも
# 入れないため。
$staging = Join-Path $env:TEMP "rinowa-src-$version"
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Force -Path $staging | Out-Null

# ファイルの一覧はディスクを歩いて作らず、git から取る。
#
# 以前は木全体を歩いて、一覧に書いたものを除いていた。除外の一覧は、誰かが思い付いた
# ファイルしか知らない。会話の断片が*名前*になった空ファイル2つと、108KB の
# firestore-debug.log がそうやって zip に入った。ログは .gitignore に入っていたのに
# 意味が無かった。箱詰めの手順が一度もそれを見ていなかったから。
#
# 'git ls-files --cached --others --exclude-standard' は .gitignore が言う view そのもの
# （追跡しているもの全部＋無視されていない未追跡）。新しい種類のごみは ignore に1回
# 書けば書庫からも外れる。2箇所で覚えておく必要が無くなる。
#
# git は仮定せず探す。この企画を普段動かしているシェルには PATH にあり、PowerShell には
# 無い。そこで出る失敗（"'git' is not recognized"）は10分後、APK だけができて書庫が
# 無い状態で来る。
#
# パスは全部スラッシュ。PowerShell は受け付けるし、バックスラッシュをエスケープとして
# 扱うもので編集しても壊れない。
#
# git が別の場所にある機械は tools/local.ps1 でそう言う。あれはリポジトリにも書庫にも
# 入らない。1台にしか当てはまらないことは、配られるスクリプトではなくそちらに置く。
$local = Join-Path $PSScriptRoot 'local.ps1'
if (Test-Path $local) { . $local }

$git = $env:RINOWA_GIT
if (-not $git -or -not (Test-Path $git)) {
    $git = (Get-Command git -ErrorAction SilentlyContinue).Source
}
if (-not $git) {
    $candidates = @(
        'C:/Program Files/Git/cmd/git.exe',
        'C:/Program Files (x86)/Git/cmd/git.exe',
        (Join-Path $env:LOCALAPPDATA 'Programs/Git/cmd/git.exe')
    )
    $git = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $git) { throw "git not found; set \$env:RINOWA_GIT in tools/local.ps1. The archive is built from git's view of the tree, so there is nothing to build it from" }

Push-Location $repo
$listed = & $git ls-files --cached --others --exclude-standard
$listExit = $LASTEXITCODE
Pop-Location
if ($listExit -ne 0) { throw "git ls-files failed; refusing to build an archive by guessing" }

# これに当たるものは、この機械を出る zip に絶対入れない。
# 書庫を作ったあとにもう一度確認する（下の防護を参照）。
$secretPattern = '(^|[\\/])(keystore\.properties|local\.properties|google-services\.json|turn_cloudflare\.php|turn_secret\.php)$|\.jks$|\.keystore$'

# ただのパスに見えない名前は、そもそもファイルとして意図されていない名前。
#
# すべての発端になった失敗への防護。シェルの打ち間違いで文がファイル名になり、中身は
# 空で、誰も気付かず、その文が出荷される。**問題は中身が空なことではなく、名前が漏れること。**
#
# 「駄目なもの」ではなく「許すもの」で書く。面白い事故は、誰も予想しなかったものだから。
# この企画のパスは ASCII の英数字・ドット・ハイフン・アンダースコア・区切りだけ。
# それ以外は、一緒に運ばれるのではなくリリースを止める。非 ASCII のファイル名を
# 正当に足す日が来たら、意識して書き換えるのがこの行。
$allowedName = "^[A-Za-z0-9._/-]+$"

$junk = $listed | Where-Object { $_ -notmatch $allowedName }
if ($junk) {
    throw "refusing to package, these names do not look like source files: " + ($junk -join " | ")
}

# リポジトリには置くが、他人へ渡す書庫には入れないファイル。
#
# ここに名前を書かず一覧から読む。このスクリプト自身が書庫に入るので、除外する
# ファイルの名前をここに書けば、結局その名前を持ち出すことになる。
$excludeList = Join-Path $repo 'tools\archive-exclude.txt'
$archiveExcluded = @('tools/archive-exclude.txt')
if (Test-Path $excludeList) {
    $archiveExcluded += Get-Content $excludeList |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith('#') }
}

$copied = 0
foreach ($relative in $listed) {
    if ($relative -match $secretPattern) { continue }
    # outputs\ には、このスクリプトが書いた過去のリリースが入っている。その下の
    # どれもソース書庫に入るものではない。ましてや再帰的には。
    if ($relative -like 'outputs/*') { continue }

    if ($archiveExcluded -contains $relative) { continue }

    $sourceFile = Join-Path $repo ($relative -replace '/', '\')
    if (-not (Test-Path $sourceFile)) { continue }
    $target = Join-Path $staging ($relative -replace '/', '\')
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Copy-Item $sourceFile $target -Force
    $copied++
}
Write-Host "source : $copied files from git's view of the tree"

$zipOut = Join-Path $outDir "rinowa-$version-source.zip"
if (Test-Path $zipOut) { Remove-Item $zipOut -Force }
Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zipOut
Remove-Item $staging -Recurse -Force

# 最後の砦。上の除外一覧は、誰かが更新を覚えていないと効かない濾し器。こちらは
# 出来上がった書庫のほうを検査するので、新しく足された秘密がそのまま通り抜けない。
# 漏れた署名パスワードは共有を取り消せないので、報告ではなく書庫を破棄する。
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
