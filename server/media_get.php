<?php
/**
 * ダウンロード。バイト範囲つき。
 *
 *   GET media_get.php?id=<sha256>&class=perm|orig
 *   Authorization: Bearer <firebase id token>
 *   Range: bytes=<start>-<end>        （任意）
 *
 * 範囲がこの窓口の要点。無ければ、動画は落とし終わるまで見られない。モバイル回線で
 * 200MB なら、何かが動き出すまで数分ぐるぐるを眺めることになる。あれば、プレイヤーは
 * 最初の数秒を取って再生を始め、先を読み続ける。途中へ飛ぶ費用も、その手前全部ではなく
 * その途中だけ。
 *
 * つまり**自動では何も落ちない**。動画が30本並んだスレッドでも、取るのは1枚目だけで、
 * 誰かが再生を押すまで本体は取りに行かない。
 *
 * 保管先の選び方が効いてくるのがここ。Firestore のドキュメントにはバイト範囲の概念が
 * 無いので、同じ機能をあちらでやると、最初の1フレームを出す前に全部の塊を落とすことになる。
 *
 * 範囲と暗号化の関係。ここのバイト列は暗号文で、ファイル全体にかける普通の AES-GCM では
 * この配り方はできない（最後に認証タグが1つなので、信用するには全部読む必要がある）。
 * クライアントは**区間ごとの**構成で暗号化する（Tink の AES-GCM-HKDF-STREAMING、
 * STREAM 方式）。区間ごとにタグがあるので、バイト範囲は区間の集まりに対応し、
 * 単独で復号できる。
 *
 * サーバーはそれを何も知らない。バイト列を配るだけ。
 *
 * 中身が読めないのに認証する理由。中身を守るためではない（暗号文が自分で守っている）。
 * 開いた窓口が、見つけた人にとっての無料の帯域になるのを止めるため。
 */

declare(strict_types=1);

require_once __DIR__ . '/media_common.php';

header('X-Content-Type-Options: nosniff');

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'GET' && ($_SERVER['REQUEST_METHOD'] ?? '') !== 'HEAD') {
    fail(405, 'method_not_allowed');
}

mediaRequireUid();

$id = (string) ($_GET['id'] ?? '');
$class = (string) ($_GET['class'] ?? 'perm');
if (!mediaValidId($id)) fail(400, 'bad_id');
if ($class !== 'perm' && $class !== 'orig') fail(400, 'bad_class');

$path = mediaPath($id, $class);
if (!is_file($path)) {
    // 期限を過ぎたオリジナルは「無い」のではなく「消えた」ので、クライアントは
    // 言い分けるべき。「圧縮版はまだある」は役に立つが、「見つかりません」は立たない。
    fail(404, $class === 'orig' ? 'original_expired' : 'not_found');
}

$size = (int) filesize($path);

// わざと不透明にする。サーバーはここで写真と動画を区別できないし（持っているのは
// 暗号文）、読めない名前から型を推測するのは嘘をつくこと。
header('Content-Type: application/octet-stream');
header('Accept-Ranges: bytes');
// 内容アドレスなので、この名前のバイト列が変わることはない。キャッシュは好きなだけ
// 持っていてよい。download.php とは逆（あちらは latest の名前のまま中身が変わる）。
header('Cache-Control: private, max-age=31536000, immutable');
header('ETag: "' . $id . '"');

$start = 0;
$end = $size - 1;

$range = $_SERVER['HTTP_RANGE'] ?? '';
if ($range !== '') {
    // 範囲は1つだけ。複数範囲は規格上は正しいが、要求してくるプレイヤーは無い。
    if (!preg_match('/^bytes=(\d*)-(\d*)$/', trim($range), $m)) {
        header("Content-Range: bytes */$size");
        fail(416, 'bad_range');
    }

    if ($m[1] === '' && $m[2] === '') {
        header("Content-Range: bytes */$size");
        fail(416, 'bad_range');
    }

    if ($m[1] === '') {
        // `bytes=-500` は末尾500バイト。MP4 の索引がファイルの最後に書かれている
        // ときに、プレイヤーがそれを探すのに使う。
        $length = (int) $m[2];
        if ($length <= 0) {
            header("Content-Range: bytes */$size");
            fail(416, 'bad_range');
        }
        $start = max(0, $size - $length);
    } else {
        $start = (int) $m[1];
        if ($m[2] !== '') $end = (int) $m[2];
    }

    if ($start > $end || $start >= $size) {
        header("Content-Range: bytes */$size");
        fail(416, 'bad_range');
    }
    if ($end >= $size) $end = $size - 1;

    http_response_code(206);
    header("Content-Range: bytes $start-$end/$size");
}

$length = $end - $start + 1;
header('Content-Length: ' . $length);

if (($_SERVER['REQUEST_METHOD'] ?? '') === 'HEAD') exit;

$handle = fopen($path, 'rb');
if ($handle === false) fail(500, 'read_failed');
fseek($handle, $start);

// 丸ごと読まずにブロックで流す。でないと 200MB の範囲を送り返すのに、PHP 側で
// 200MB のメモリが要る。
$remaining = $length;
while ($remaining > 0 && !feof($handle)) {
    $block = fread($handle, (int) min(262144, $remaining));
    if ($block === false) break;
    echo $block;
    $remaining -= strlen($block);
    // これが無いと、読むのが遅い相手には、膨らみ続ける出力バッファから配ることになる。
    if (connection_aborted()) break;
    flush();
}
fclose($handle);
