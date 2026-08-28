<?php
/**
 * メディアの窓口で共有する土台。
 *
 * ここに保存されるものは**すべて暗号文**。クライアントが Tink の
 * AES-GCM-HKDF-STREAMING で暗号化してから上げ、鍵は Firestore のメッセージ
 * ドキュメントを通る。あちらのセキュリティルールが、すでに会話の参加者だけに制限して
 * いる。それがアクセス制御の設計そのもので、意図的にそうしている。
 *
 * > HTTP で配るファイルの前にルールエンジンは無い。当てにくい URL は保証ではなく、
 * > 漏れないことへの願望。Firestore のルールは保証。だから保証は、すでに効いている
 * > 場所——鍵——に置いたままにし、この保管庫には鍵無しでは無意味なバイト列だけを置く。
 *
 * はっきり書くべき帰結: **このサーバーを運用する人は、そこにあるものを読めない。**
 * 置き換える前の Firestore Blob より強い（あちらでは写真のバイト列が Firebase の
 * コンソールから読めた）。
 *
 * 正直な限界: これは end-to-end 暗号化ではない。Firebase のコンソールに入れる人は
 * 鍵のドキュメントを読んでファイルを取れる。以前と同じ水準であって悪化ではない。
 * 本物の E2EE が入れば鍵は封の内側へ移り、この保管庫は誰にとっても不透明になる。
 *
 * 区分は2つ、寿命も2つ:
 *
 * | 区分   | 中身                     | 保持 |
 * |--------|--------------------------|------|
 * | `perm` | 圧縮版                   | 消すまで |
 * | `orig` | 送信者が選んだオリジナル | ORIG_TTL_DAYS で掃除 |
 *
 * オリジナルがあるのは、「オリジナルを保存」が撮った端末だけでなく**両方**で本当の
 * 選択肢になるため。永久には置かない。クラウドのオブジェクトストレージを使わない
 * 理由が、溜めないことだから。
 */

declare(strict_types=1);

require_once __DIR__ . '/push_auth.php';

/** どのウェブルートよりも外。ここへはこれらの窓口を通す以外に届かない。 */
const MEDIA_ROOT = '/home/nextlab/echo-media';

/** 30日。過ぎるとオリジナルは掃除され、圧縮版だけが残る。 */
const ORIG_TTL_DAYS = 30;

/** 1要求あたりの上限。post_max_size より十分小さくして、PHP 自身に引っかからないように。 */
const MAX_CHUNK_BYTES = 8 * 1024 * 1024;

const MAX_PERM_BYTES = 300 * 1024 * 1024;
const MAX_ORIG_BYTES = 1000 * 1024 * 1024;

/**
 * メディアの id は**暗号文**の SHA-256（小文字16進）。
 *
 * 平文ではなく暗号文を hash するから、サーバーは中身を読めないまま、受け取ったものを
 * 検証できる。平文の検査はクライアント側が別にやる。
 */
function mediaValidId(string $id): bool
{
    return preg_match('/^[0-9a-f]{64}$/', $id) === 1;
}

function mediaClassLimit(string $class): int
{
    return $class === 'orig' ? MAX_ORIG_BYTES : MAX_PERM_BYTES;
}

/** 2段に分ける。10万個のファイルが1つの平らなディレクトリにあるのは間違い。 */
function mediaPath(string $id, string $class): string
{
    return MEDIA_ROOT . '/objects/' . substr($id, 0, 2) . '/' . substr($id, 2, 2) . "/$id.$class";
}

function mediaMetaPath(string $id, string $class): string
{
    return mediaPath($id, $class) . '.json';
}

function mediaPartPath(string $id, string $class): string
{
    return MEDIA_ROOT . '/parts/' . $id . '.' . $class . '.part';
}

function mediaEnsureDirs(string $path): void
{
    $dir = dirname($path);
    if (!is_dir($dir) && !mkdir($dir, 0700, true) && !is_dir($dir)) {
        fail(500, 'storage_unavailable');
    }
}

/**
 * bearer トークン。無ければ 401。
 *
 * どの窓口もサインインを要求する。バイト列がそれ無しに読めるからではない（読めない）。
 * 認証の無いアップロードの窓口は、見つけた人にとっての無料ホスティングだから。
 */
function mediaRequireUid(): string
{
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if (!preg_match('/^Bearer\s+(\S+)$/i', $header, $m)) fail(401, 'no_token');
    return verifyIdToken($m[1]);
}

/**
 * 期限を過ぎたオリジナルと、放置された途中ファイルを消す。
 *
 * cron ではなくここで走らせる理由。このアカウントの crontab にはすでにブログの行が
 * 入っていて、共有の crontab を書き換えて1行足すのは、他人の行を消す手口でもある。
 * なので掃除は通信に相乗りする。だいたい50回に1回の要求が実行し、1日に数個ずつ増える
 * 保管庫にはそれで十分すぎる。
 *
 * 同時に走っても安全に書いてある。どの手順も期限の過ぎたファイルの unlink で、
 * 2つのプロセスが同じファイルを消そうとしても両方成功する。
 */
function mediaMaybeSweep(): void
{
    if (random_int(1, 50) !== 1) return;
    mediaSweep();
}

function mediaSweep(): int
{
    $removed = 0;
    $now = time();

    $objects = MEDIA_ROOT . '/objects';
    if (is_dir($objects)) {
        $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator(
            $objects,
            FilesystemIterator::SKIP_DOTS
        ));
        foreach ($it as $file) {
            if (!$file->isFile()) continue;
            $name = $file->getFilename();
            if (!str_ends_with($name, '.orig')) continue;
            if ($now - $file->getMTime() < ORIG_TTL_DAYS * 86400) continue;
            @unlink($file->getPathname());
            @unlink($file->getPathname() . '.json');
            $removed++;
        }
    }

    // 途中で止まったアップロードは途中ファイルを残す。1日は、どんなアップロードより
    // 長く、「永久」よりはるかに短い。
    $parts = MEDIA_ROOT . '/parts';
    if (is_dir($parts)) {
        foreach (glob($parts . '/*.part') ?: [] as $part) {
            if ($now - (int) @filemtime($part) > 86400) {
                @unlink($part);
                $removed++;
            }
        }
    }

    return $removed;
}
