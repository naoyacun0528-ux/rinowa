#!/usr/bin/env bash
#
# ビルドを、端末から届く場所に置く。
#
#   tools/publish.sh 0.8.3
#
# その版の成果物を上げ、固定の名前で置き直す。渡したリンクが変わらないように:
#
#   https://echo.nextlab.blog/<slug>/download.php?f=rinowa-latest-release.apk
#
# ホスト名は echo.nextlab.blog のまま。誰にも見えないし、動かすと既に入っている
# ビルドが取り残される。人が読むのはファイル名のほう。
#
# 公開するのは release ビルドだけ。
#
# 以前は固定 URL で rinowa-latest-debug.apk も配っていた。debug は debug 鍵で署名され
# android:debuggable が付く。つまり端末を持っている人はプロセスにデバッガを繋いで、
# メモリの中身を読める。他のどこでも E2EE していたメッセージも含めて。誰が会話を
# 読めるかについてのこの企画の保証は、debuggable なビルドで終わる。
#
# 試すのが作った本人だけの間は耐えられた。他人にリンクを渡した瞬間に耐えられなくなり、
# 存在するリンクは渡されるもの。debug APK は手元用に作り続けるが、公開はしない。
#
# 版ごとの複製は隣に残す。比べる必要が出たとき、古いビルドを名前で取れるように。
#
# 直リンクではなく download.php を通す理由: Android の Chrome は Android パッケージだと
# 認識したものに独自の検査をかけ、100% のまま止まり続けることがある。しかも Xserver は
# 静的ファイルを nginx で配り、そこでは .htaccess が効かない。server/download.php を参照。

set -euo pipefail

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
    echo "usage: tools/publish.sh <version>   e.g. tools/publish.sh 0.8.3" >&2
    exit 1
fi

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL="$REPO/outputs/$VERSION"
[ -d "$LOCAL" ] || { echo "no such build: $LOCAL" >&2; exit 1; }

KEY="$HOME/.ssh/nextlab_echo_ed25519"
HOST="nextlab@nextlab.xsrv.jp"
PORT=10022
SLUG="$(cat "$REPO/outputs/0.6.1/.download-slug" 2>/dev/null || echo dly5sfc4x1)"
REMOTE="/home/nextlab/nextlab.blog/public_html/echo.nextlab.blog/$SLUG"

quiet() { grep -vE "WARNING: connection|store now|may need to be upgraded" || true; }

echo "publishing $VERSION -> $REMOTE"

cd "$LOCAL"
# ソース書庫は**上げない**。
#
# リンクを付けたことが一度も無く（渡すのは APK とノートだけ）、誰にも取られないまま
# 30個が公開ディレクトリに座って、URL の形を当てられるのを待っていた。公開されていて
# リンクされていない成果物は、非公開ではなく未掲載で、その違いは誰かがディレクトリを
# 漁った最初の1回で効いてくる。書庫は tools/release.ps1 が手元に作る。人に頼まれたら
# その人に送る。
scp -i "$KEY" -P "$PORT" -o BatchMode=yes \
    rinowa-"$VERSION"-release.apk \
    RELEASE-"$VERSION".md \
    "$HOST:$REMOTE/" 2>&1 | quiet

# シンボリックリンクではなく複製。PHP の readfile はリンクをたどれるが、複製なら
# あとから古い版を消しても壊れない。
ssh -i "$KEY" -p "$PORT" -o BatchMode=yes "$HOST" "
    cd $REMOTE &&
    cp -f rinowa-$VERSION-release.apk rinowa-latest-release.apk &&
    rm -f rinowa-latest-debug.apk rinowa-*-debug.apk echo-latest-debug.apk echo-*-debug.apk &&
    cp -f RELEASE-$VERSION.md       RELEASE-latest.md &&
    echo $VERSION > VERSION.txt
" 2>&1 | quiet

BASE="https://echo.nextlab.blog/$SLUG/download.php?f="
echo
echo "  release ${BASE}rinowa-latest-release.apk"
echo "  notes   ${BASE}RELEASE-latest.md"
echo

curl -sk -o /dev/null -w "  check: http %{http_code}\n" -r 0-100 "${BASE}rinowa-latest-release.apk"

# 上でサーバーから debug のリンクを消してある。仮定せず確認する。ここで 404 になるのが
# 目的で、200 なら、その URL を持っている人にまだ debuggable なビルドが配られている。
curl -sk -o /dev/null -w "  debug link now: http %{http_code} (404 expected)\n" \
    -r 0-100 "${BASE}rinowa-latest-debug.apk"
