<?php
/**
 * ビルドのファイルを、最後まで受け取れるブラウザに渡す。
 *
 * APK への素のリンクを邪魔するものが2つある。
 *
 *  1. **Android の Chrome** は Android パッケージの MIME 型を認識し、受け取り終えた
 *     ファイルに独自の安全検査をかける。その検査は 100% のまま止まり続けることがある。
 *     全バイト届いているのに完了しない。中身を不透明な列として返せばその経路に入らない。
 *     名前の `.apk` は残すので、押せばパッケージインストーラが開く。
 *
 *  2. **Xserver は静的ファイルを nginx で配る**。Apache の手前なので `.htaccess` は
 *     その要求を見ない。PHP の応答は静的ではないので、サイト全体の設定を変えずに
 *     ヘッダを付けられる唯一の場所になる。
 *
 * パスの安全性。ファイル名は basename に落としてから、実際にこのディレクトリにある
 * ファイルと照合する。何を打っても、外側へは届かない。
 */

declare(strict_types=1);

$requested = basename((string) ($_GET['f'] ?? ''));
$directory = __DIR__;

$allowed = array_values(array_filter(
    scandir($directory) ?: [],
    static fn ($name) => preg_match('/\.(apk|zip|md)$/i', $name) === 1
));

if ($requested === '' || !in_array($requested, $allowed, true)) {
    http_response_code(404);
    header('Content-Type: text/plain; charset=utf-8');
    echo "not found\n\n利用できるファイル:\n" . implode("\n", $allowed) . "\n";
    exit;
}

$path = $directory . DIRECTORY_SEPARATOR . $requested;
$size = filesize($path);

// わざと不透明に。上を参照。
header('Content-Type: application/octet-stream');
header('Content-Disposition: attachment; filename="' . $requested . '"');
header('Content-Length: ' . $size);
header('Accept-Ranges: none');
header('X-Content-Type-Options: nosniff');
// no-transform だけでなく no-store も。latest は名前を保ったまま中身が変わるので、
// キャッシュされると、いまの名前で前のビルドが返る。固定のダウンロード URL が
// 招き寄せる唯一の失敗がそれ。
header('Cache-Control: no-store, no-transform, must-revalidate');
header('Pragma: no-cache');

// メモリに読まずに流す。でないと 21MB の APK に、理由も無く 21MB の PHP メモリが要る。
// 共有ホスティングはそういうことに意見を持っている。
$handle = fopen($path, 'rb');
if ($handle === false) {
    http_response_code(500);
    exit;
}
while (!feof($handle)) {
    echo fread($handle, 262144);
    flush();
}
fclose($handle);
