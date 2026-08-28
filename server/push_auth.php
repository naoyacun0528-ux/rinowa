<?php
/**
 * サーバー側の認証。必要な Rinowa の窓口で共有する。
 *
 * push.php から切り出してある。ice.php も、そのあとに増えるものも、トークンを*同じ*
 * やり方で検証するため。署名の検査が2つあると、微妙に間違える場所が2つになり、
 * 間違っているのはいつも誰も見ていないほう。
 *
 * include しただけでは何も動かない。定義するだけ。
 */

declare(strict_types=1);

const PROJECT_ID    = 'echo-cfe37';
const SERVICE_KEY   = '/home/nextlab/echo-secrets/service-account.json';
const GOOGLE_CERTS  = 'https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com';
const FCM_ENDPOINT  = 'https://fcm.googleapis.com/v1/projects/' . PROJECT_ID . '/messages:send';
const FIRESTORE     = 'https://firestore.googleapis.com/v1/projects/' . PROJECT_ID . '/databases/(default)/documents';

const MAX_BODY_CHARS = 200;
const TOKEN_CACHE    = '/home/nextlab/echo-secrets/.access_token';
const CERT_CACHE     = '/home/nextlab/echo-secrets/.google_certs';

function fail(int $status, string $code): never
{
    http_response_code($status);
    // コードだけを返す。要求のどこが悪かったかは書かない。認証の窓口で詳しいエラーを
    // 返すのは、探る側への道具になる。
    echo json_encode(['error' => $code]);
    exit;
}

function base64UrlDecode(string $input): string
{
    return base64_decode(strtr($input, '-_', '+/') . str_repeat('=', (4 - strlen($input) % 4) % 4));
}

function base64UrlEncode(string $input): string
{
    return rtrim(strtr(base64_encode($input), '+/', '-_'), '=');
}

function httpGet(string $url, array $headers = []): ?string
{
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER     => $headers,
        CURLOPT_TIMEOUT        => 10,
    ]);
    $response = curl_exec($ch);
    $status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    return ($status >= 200 && $status < 300 && is_string($response)) ? $response : null;
}

function httpDelete(string $url, array $headers = []): bool
{
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CUSTOMREQUEST  => 'DELETE',
        CURLOPT_HTTPHEADER     => $headers,
        CURLOPT_TIMEOUT        => 10,
    ]);
    curl_exec($ch);
    $status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    return $status >= 200 && $status < 300;
}

function httpPostJson(string $url, array $payload, array $headers): array
{
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POST           => true,
        CURLOPT_POSTFIELDS     => json_encode($payload, JSON_UNESCAPED_UNICODE),
        CURLOPT_HTTPHEADER     => array_merge(['Content-Type: application/json'], $headers),
        CURLOPT_TIMEOUT        => 15,
    ]);
    $response = curl_exec($ch);
    $status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    return [$status, is_string($response) ? $response : ''];
}

/**
 * Firebase の ID トークンを検証し、その uid を返す。
 *
 * 署名、発行者、対象、期限を見る。どれか1つでも飛ばすと検査は飾りになる。検証していない
 * JWT は呼び出し元が書いた文字列でしかなく、その uid は好きに打った値でしかない。
 */
function verifyIdToken(string $jwt): string
{
    $parts = explode('.', $jwt);
    if (count($parts) !== 3) fail(401, 'bad_token');

    [$rawHeader, $rawPayload, $rawSignature] = $parts;
    $header  = json_decode(base64UrlDecode($rawHeader), true);
    $payload = json_decode(base64UrlDecode($rawPayload), true);
    if (!is_array($header) || !is_array($payload)) fail(401, 'bad_token');

    $certs = json_decode(readGoogleCerts(), true);
    $kid = $header['kid'] ?? '';
    if (!isset($certs[$kid])) fail(401, 'unknown_key');

    $publicKey = openssl_pkey_get_public($certs[$kid]);
    if ($publicKey === false) fail(500, 'key_error');

    $verified = openssl_verify(
        "$rawHeader.$rawPayload",
        base64UrlDecode($rawSignature),
        $publicKey,
        OPENSSL_ALGO_SHA256
    );
    if ($verified !== 1) fail(401, 'bad_signature');

    $now = time();
    if (($payload['aud'] ?? '') !== PROJECT_ID) fail(401, 'bad_audience');
    if (($payload['iss'] ?? '') !== 'https://securetoken.google.com/' . PROJECT_ID) fail(401, 'bad_issuer');
    if (($payload['exp'] ?? 0) < $now) fail(401, 'expired');
    if (($payload['auth_time'] ?? 0) > $now + 60) fail(401, 'bad_auth_time');

    $uid = $payload['sub'] ?? '';
    if ($uid === '') fail(401, 'no_subject');
    return $uid;
}

/** Google はこれを入れ替える。要求ごとに取らず1時間キャッシュする。 */
function readGoogleCerts(): string
{
    if (is_readable(CERT_CACHE) && (time() - filemtime(CERT_CACHE)) < 3600) {
        return (string) file_get_contents(CERT_CACHE);
    }
    $fresh = httpGet(GOOGLE_CERTS);
    if ($fresh === null) {
        if (is_readable(CERT_CACHE)) return (string) file_get_contents(CERT_CACHE);
        fail(503, 'certs_unavailable');
    }
    file_put_contents(CERT_CACHE, $fresh);
    chmod(CERT_CACHE, 0600);
    return $fresh;
}

/** サービスアカウントの OAuth2 アクセストークン。自己署名の JWT で交換する。 */
function serviceAccessToken(): string
{
    if (is_readable(TOKEN_CACHE)) {
        $cached = json_decode((string) file_get_contents(TOKEN_CACHE), true);
        if (is_array($cached) && ($cached['expires_at'] ?? 0) > time() + 60) {
            return $cached['token'];
        }
    }

    $key = json_decode((string) file_get_contents(SERVICE_KEY), true);
    if (!is_array($key)) fail(500, 'no_service_key');

    $now = time();
    $header = base64UrlEncode(json_encode(['alg' => 'RS256', 'typ' => 'JWT']));
    $claims = base64UrlEncode(json_encode([
        'iss'   => $key['client_email'],
        'scope' => 'https://www.googleapis.com/auth/firebase.messaging '
                 . 'https://www.googleapis.com/auth/datastore',
        'aud'   => 'https://oauth2.googleapis.com/token',
        'iat'   => $now,
        'exp'   => $now + 3600,
    ]));

    $signature = '';
    openssl_sign("$header.$claims", $signature, $key['private_key'], OPENSSL_ALGO_SHA256);
    $assertion = "$header.$claims." . base64UrlEncode($signature);

    $ch = curl_init('https://oauth2.googleapis.com/token');
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POST           => true,
        CURLOPT_POSTFIELDS     => http_build_query([
            'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
            'assertion'  => $assertion,
        ]),
        CURLOPT_TIMEOUT        => 15,
    ]);
    $raw = curl_exec($ch);
    curl_close($ch);

    $result = json_decode((string) $raw, true);
    if (!isset($result['access_token'])) fail(500, 'oauth_failed');

    file_put_contents(TOKEN_CACHE, json_encode([
        'token'      => $result['access_token'],
        'expires_at' => $now + (int) ($result['expires_in'] ?? 3600),
    ]));
    chmod(TOKEN_CACHE, 0600);

    return $result['access_token'];
}

function firestoreGet(string $path, string $accessToken): ?array
{
    $raw = httpGet(FIRESTORE . '/' . $path, ["Authorization: Bearer $accessToken"]);
    return $raw === null ? null : json_decode($raw, true);
}

/** Firestore の REST 形式は、どの値も型のタグで包む。 */
function fieldString(array $document, string $name): ?string
{
    return $document['fields'][$name]['stringValue'] ?? null;
}

function fieldStringArray(array $document, string $name): array
{
    $values = $document['fields'][$name]['arrayValue']['values'] ?? [];
    return array_values(array_filter(array_map(
        static fn ($v) => $v['stringValue'] ?? null,
        $values
    )));
}
