<?php
/**
 * 通話に使う ICE サーバーの一覧をアプリに渡す。
 *
 * アプリの定数ではなくサーバーの窓口にしている理由は2つ。大事なのは2つ目。
 *
 *  1. **TURN の資格情報は短命でなければならない。** APK に焼いた固定のパスワードは
 *     1分ほどで抜き出せ、そのあと中継は他人の無料の帯域になる。標準の答え
 *     （TURN REST API）は、利用者名を `<期限のUNIX時刻>:<uid>`、パスワードを
 *     HMAC-SHA1(利用者名, 共有秘密) にするもので、秘密を持つサーバーが要る。それがここ。
 *
 *  2. **TURN の提供元は変わる。** いまは公開の試験用中継で、明日は Cloudflare か
 *     VPS 上の coturn かもしれない。一覧をアプリが持っていると、変更のたびに
 *     アプリのリリースとダウンロードが要り、半分の端末が古い一覧のまま動く時間ができる。
 *     ここならファイルを1つ直すだけ。
 *
 * 認証は push.php と同じ形。Firebase の ID トークンを Google の証明書で検証し、
 * uid は呼び出し元の自己申告ではなくトークンから取る。それが無ければ、この窓口は
 * インターネット全体への無料の中継になる。
 */

declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

require_once __DIR__ . '/push_auth.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    http_response_code(405);
    echo json_encode(['error' => 'method']);
    exit;
}

$authHeader = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
if (!preg_match('/^Bearer\s+(.+)$/i', $authHeader, $matches)) {
    http_response_code(401);
    echo json_encode(['error' => 'no_token']);
    exit;
}

$uid = verifyIdToken(trim($matches[1]));

// -----------------------------------------------------------------------------------------
// 一覧
// -----------------------------------------------------------------------------------------

$servers = [
    // STUN は費用ゼロで、家庭の回線の大半はそれだけで解決する。
    ['urls' => ['stun:stun.l.google.com:19302']],
    ['urls' => ['stun:stun1.l.google.com:19302']],
];

/**
 * TURN。
 *
 * turn_secret.php があればそこから埋める。あのファイルは提供元のホストと共有秘密を
 * 持っていて、keystore.properties と同じ理由でリポジトリに入れていない。無ければ
 * この窓口は STUN だけで答え、同じ回線の2台の通話は動き続ける。秘密が無いときは
 * 壊れるのではなく落ちるべき。
 */
$secretFile = __DIR__ . '/turn_secret.php';
if (is_file($secretFile)) {
    /** @var array{host:string, secret:string, ttl?:int} $turn */
    $turn = require $secretFile;

    $ttl = $turn['ttl'] ?? 3600;
    $expiry = time() + $ttl;
    $username = $expiry . ':' . $uid;
    $credential = base64_encode(hash_hmac('sha1', $username, $turn['secret'], true));

    $servers[] = [
        'urls' => [
            'turn:' . $turn['host'] . '?transport=udp',
            // TCP も入れる。UDP を丸ごと落とす回線があり、そこでは UDP でしか
            // 届かない中継は中継として存在しないのと同じ。
            'turn:' . $turn['host'] . '?transport=tcp',
        ],
        'username' => $username,
        'credential' => $credential,
    ];
} elseif (is_file(__DIR__ . '/turn_cloudflare.php')) {
    /**
     * Cloudflare Realtime TURN。
     *
     * Cloudflare は HMAC の方式を使わず、長期のトークンで API を叩いて資格情報を
     * 発行する。そのトークンはここに留まり、**アプリには絶対に渡らない**。アプリが
     * 見るのは、返ってきた数時間ぶんの資格情報だけ。APK を分解しても、出てくるのは
     * すでに期限切れのもの。
     *
     * ここでの失敗はわざと柔らかい。応答には STUN が残るので、Cloudflare の障害が
     * 奪うのは中継であって、発信そのものではない。
     */
    $cloudflare = cloudflareIceServers(require __DIR__ . '/turn_cloudflare.php');
    if ($cloudflare !== null) {
        $servers = array_merge($servers, $cloudflare);
    }
} elseif (is_file(__DIR__ . '/turn_static.php')) {
    // HMAC ではなく固定の資格情報を出す提供元。弱いほうの構成が変数の中に隠れず、
    // ファイル一覧の上で見えるように、別ファイルに分けてある。
    $servers = array_merge($servers, require __DIR__ . '/turn_static.php');
}

/**
 * Cloudflare に短命の TURN 資格情報を求める。
 *
 * 有効期間のほとんどはディスクにキャッシュする。無いと、どこで発信しても、この共有
 * ホストから Cloudflare への往復が、端末が候補を集め始める前に入る。毎回同じ値のために、
 * すべての通話の頭に数秒足すことになる。
 *
 * @return array<int, array<string, mixed>>|null 失敗したら null。呼び出し側は
 *   何も返さずに終わるのではなく STUN に落ちる。
 */
function cloudflareIceServers(array $config): ?array
{
    $cache = '/home/nextlab/echo-secrets/.turn_cloudflare';
    $ttl = (int) ($config['ttl'] ?? 3600);

    if (is_readable($cache)) {
        $cached = json_decode((string) file_get_contents($cache), true);
        if (is_array($cached) && ($cached['expires_at'] ?? 0) > time() + 300) {
            return $cached['servers'];
        }
    }

    $keyId = (string) ($config['keyId'] ?? '');
    $token = (string) ($config['apiToken'] ?? '');
    if ($keyId === '' || $token === '' || str_contains($keyId, 'ここに')) return null;

    [$status, $body] = httpPostJson(
        "https://rtc.live.cloudflare.com/v1/turn/keys/$keyId/credentials/generate-ice-servers",
        ['ttl' => $ttl],
        ["Authorization: Bearer $token"]
    );
    if ($status < 200 || $status >= 300) return null;

    $decoded = json_decode($body, true);
    if (!is_array($decoded) || !isset($decoded['iceServers'])) return null;

    // API はオブジェクトを1つ返す。配列も受けるようにしておくのは1行で済み、
    // 向こうの変更で中継の一覧が黙って空にならずに済む。
    $raw = $decoded['iceServers'];
    $servers = isset($raw['urls']) ? [$raw] : $raw;
    if (!is_array($servers) || $servers === []) return null;

    $normalised = [];
    foreach ($servers as $entry) {
        if (!isset($entry['urls'])) continue;
        $normalised[] = [
            'urls' => is_array($entry['urls']) ? array_values($entry['urls']) : [$entry['urls']],
            'username' => (string) ($entry['username'] ?? ''),
            'credential' => (string) ($entry['credential'] ?? ''),
        ];
    }
    if ($normalised === []) return null;

    @file_put_contents(
        $cache,
        json_encode(['expires_at' => time() + $ttl, 'servers' => $normalised])
    );
    @chmod($cache, 0600);

    return $normalised;
}

echo json_encode([
    'iceServers' => $servers,
    // アプリはこの時刻まではキャッシュを使い、過ぎたら取り直す。絶対時刻で送るのは、
    // 2つの時計がそれ以外で一致している必要が無いから。
    'expiresAt' => time() + 1800,
]);
