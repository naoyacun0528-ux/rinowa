<?php
/**
 * アップロード。再開できる分割で。
 *
 *   POST media.php?id=<sha256>&class=perm|orig&offset=<bytes>[&final=1]
 *   Authorization: Bearer <firebase id token>
 *   body: この分割ぶんの生バイト
 *
 * 続きがある間は `{"received":<n>}`、組み上がったファイルを検証して所定の場所へ移したら
 * `{"stored":true}` を返す。
 *
 * 1回の POST にしない理由。200MB の動画をモバイル回線で送れば必ず途切れる。1回の
 * POST だと復帰は最初からやり直すしかなく、エレベーターに乗るたびに 200MB を送り直す
 * 機能は二度と使われない。クライアントは数MBずつ送り、失敗したらどこまで届いたかを聞く。
 *
 * `offset` はサーバーがすでに持っている量と一致していなければならない。これは seek では
 * なく、クライアントが「こう思っている」と申告するもの。食い違いを、穴に書き込む代わりに
 * 捕まえる。穴のあるファイルもハッシュは取れて保管もされ、数か月後に復号できず、
 * 原因を指すものが何も無い。
 *
 * id を中身と照合する理由。id は暗号文の SHA-256 なので、サーバーは**中身をまったく
 * 読めないまま**、announce されたとおりのものを受け取ったと確認できる。名前と合わない
 * バイト列は捨てる。検証しない内容アドレスは、ただのファイル名。
 */

declare(strict_types=1);

require_once __DIR__ . '/media_common.php';

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') fail(405, 'method_not_allowed');

$uid = mediaRequireUid();

$id = (string) ($_GET['id'] ?? '');
$class = (string) ($_GET['class'] ?? 'perm');
$offset = (int) ($_GET['offset'] ?? 0);
$final = ($_GET['final'] ?? '') === '1';

if (!mediaValidId($id)) fail(400, 'bad_id');
if ($class !== 'perm' && $class !== 'orig') fail(400, 'bad_class');
if ($offset < 0) fail(400, 'bad_offset');

$destination = mediaPath($id, $class);

// 内容アドレスなので「すでにある」は無料。同じ写真を2回転送しても、失敗後に送り直しても、
// 要求1回でバイト列は0。
if (is_file($destination)) {
    // 触れておく。送り直しがオリジナルの寿命を延ばすようにするため。まだ誰かが
    // 送り回している最中に掃除されないように。
    if ($class === 'orig') @touch($destination);
    echo json_encode(['stored' => true, 'duplicate' => true]);
    exit;
}

$part = mediaPartPath($id, $class);
mediaEnsureDirs($part);

$have = is_file($part) ? (int) filesize($part) : 0;
if ($offset !== $have) {
    // クライアントが復帰できない失敗ではない。どこから再開するかを伝える。
    http_response_code(409);
    echo json_encode(['error' => 'offset_mismatch', 'expected' => $have]);
    exit;
}

$body = file_get_contents('php://input');
if ($body === false) fail(400, 'no_body');

$chunk = strlen($body);
if ($chunk > MAX_CHUNK_BYTES) fail(413, 'chunk_too_large');

$limit = mediaClassLimit($class);
if ($have + $chunk > $limit) {
    @unlink($part);
    fail(413, 'too_large');
}

if ($chunk > 0) {
    $handle = fopen($part, 'ab');
    if ($handle === false) fail(500, 'storage_unavailable');
    // 排他で開く。同じオブジェクトを2台が送り直したとき、1つの途中ファイルに
    // 混ざってはいけない。内容アドレスなので、その衝突は珍しくなく普通に起きうる。
    if (!flock($handle, LOCK_EX)) {
        fclose($handle);
        fail(503, 'busy');
    }
    $written = fwrite($handle, $body);
    fflush($handle);
    flock($handle, LOCK_UN);
    fclose($handle);
    if ($written !== $chunk) fail(500, 'short_write');
}

$total = $have + $chunk;

if (!$final) {
    echo json_encode(['received' => $total]);
    exit;
}

// 誰かが取りに来る名前を与える前に検証する。
$actual = hash_file('sha256', $part);
if (!is_string($actual) || !hash_equals($id, $actual)) {
    @unlink($part);
    fail(422, 'hash_mismatch');
}

mediaEnsureDirs($destination);
if (!rename($part, $destination)) {
    @unlink($part);
    fail(500, 'store_failed');
}
@chmod($destination, 0600);

// 誰がいつ上げたか。media_delete.php が使い、それ以外の人には消させない。
// 会話に誰がいるかの記録はわざと持たない。この保管庫が知る筋合いも、必要も無い。
file_put_contents(
    mediaMetaPath($id, $class),
    json_encode([
        'uid' => $uid,
        'class' => $class,
        'bytes' => $total,
        'createdAt' => time(),
        'expiresAt' => $class === 'orig' ? time() + ORIG_TTL_DAYS * 86400 : null,
    ]),
    LOCK_EX
);
@chmod(mediaMetaPath($id, $class), 0600);

mediaMaybeSweep();

echo json_encode(['stored' => true, 'bytes' => $total]);
