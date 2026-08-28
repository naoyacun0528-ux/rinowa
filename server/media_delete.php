<?php
/**
 * オブジェクトを消す。
 *
 *   POST media_delete.php?id=<sha256>&class=perm|orig
 *   Authorization: Bearer <firebase id token>
 *
 * なぜ要るか。いまの取り消しはドキュメントを消して写真を残す。写真が Firestore の
 * blob にあった頃は行儀が悪いだけだったが、ファイル置き場にあるといっそう悪い。
 * ファイルには URL があり、参照していたメッセージより長く残る。「送信を取り消した」は、
 * 指す先が消えたのではなく、物が消えたという意味でなければならない。
 *
 * 消せるのは上げた人だけ。削除は、アップロード時に記録した uid と照合する。
 * 内容アドレスなので同じオブジェクトが複数の会話から参照されうる。だから
 * **見ただけのものを誰でも消せると、他人のスレッドから写真を消せる**（同じ写真を
 * 自分に送って、それを取り消せばよい）。
 *
 * 残っている不誠実さはその裏返しで、1つのオブジェクトに参照が複数あること。同じ写真が
 * 別の会話へ転送されていたら、ここで消すとあちらでも消える。直し方は参照カウントで、
 * まだ作っていない。所有と privacy の問いと一緒に Prototype 2 に挙げてある。それまでは、
 * 送信者が自分のメッセージを取り消すときだけこの窓口を使う。参照元と上げた人が
 * 同じになる唯一の場合。
 */

declare(strict_types=1);

require_once __DIR__ . '/media_common.php';

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') fail(405, 'method_not_allowed');

$uid = mediaRequireUid();

$id = (string) ($_GET['id'] ?? '');
$class = (string) ($_GET['class'] ?? '');
if (!mediaValidId($id)) fail(400, 'bad_id');
if ($class !== 'perm' && $class !== 'orig' && $class !== 'both') fail(400, 'bad_class');

$classes = $class === 'both' ? ['perm', 'orig'] : [$class];
$removed = [];

foreach ($classes as $one) {
    $path = mediaPath($id, $one);
    if (!is_file($path)) continue;

    $metaPath = mediaMetaPath($id, $one);
    $meta = is_file($metaPath) ? json_decode((string) file_get_contents($metaPath), true) : null;
    $owner = is_array($meta) ? (string) ($meta['uid'] ?? '') : '';

    // 所有者の記録が無いのは、メタデータより前のオブジェクトか、書き込みが失敗したもの。
    // 許さずに断る。証明できない主張は主張ではない。
    if ($owner === '' || !hash_equals($owner, $uid)) continue;

    @unlink($path);
    @unlink($metaPath);
    $removed[] = $one;
}

mediaMaybeSweep();

echo json_encode(['removed' => $removed]);
