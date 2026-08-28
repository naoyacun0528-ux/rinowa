<?php
/**
 * Rinowa — push 通知の送信。
 *
 * 配置先 https://echo.nextlab.blog/push.php
 *
 * ================================================================================
 * このファイルが読んでよいもの
 * ================================================================================
 *
 * このスクリプトは Firebase のサービスアカウントで認証する。サービスアカウントは
 * firestore.rules を丸ごと迂回する。あのファイルに書いた保護は全部——「管理者にも
 * 本文への特権的な読み取り経路を作らない」も含めて——このコードの前では効かない。
 *
 * だから制限はここに置く。読んでよいのは:
 *
 *   - conversations/{id}          呼び出し元が参加者か確かめるため
 *   - users/{uid}/devices         どこへ送るか知るため
 *   - users/{uid}/settings/app    受け取る側の通知設定のため
 *
 *   - conversations/{id}/messages 絶対に読まない。理由を問わず1件も。
 *
 * 通知の文面は送信者からの要求に入って来る。その送信者は、その会話の参加者だと
 * すでに検証されている。つまりこのサーバーが扱う本文は、呼び出し元がもともと
 * 持っていたものだけ。いま教えられた以上のことを、会話について知る手段が無い。
 *
 * 将来ここでメッセージの履歴が必要になったなら、その変更のほうが間違っている。
 * 別の方法を探すこと。
 *
 * ================================================================================
 * 認証
 * ================================================================================
 *
 * 呼び出し元は Firebase の ID トークンを送る。ここで Google の公開鍵に対して署名を
 * 検証し、uid はトークンから取る。要求の本文からは絶対に取らない。他人を名乗る
 * クライアントは、忘れられうる検査ではなく署名で弾かれる。
 */

declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/push_auth.php';

// ---------------------------------------------------------------- 要求

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') fail(405, 'method');

$input = json_decode((string) file_get_contents('php://input'), true);
if (!is_array($input)) fail(400, 'bad_request');

$authHeader = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
if (!preg_match('/^Bearer\s+(.+)$/i', $authHeader, $matches)) fail(401, 'no_token');

// uid は検証済みのトークンから取る。本文が身元について何を言っていても無視する。
$senderUid = verifyIdToken(trim($matches[1]));

$conversationId = (string) ($input['conversationId'] ?? '');
$senderName     = (string) ($input['senderName'] ?? '');
$body           = (string) ($input['body'] ?? '');
// 'message' か 'call'。既定は message。通話ができる前に作られたクライアントも、
// この項目の存在を知らないままこの窓口で動き続けられる。
$type           = (string) ($input['type'] ?? 'message');
$callId         = (string) ($input['callId'] ?? '');
if ($conversationId === '') fail(400, 'no_conversation');

$accessToken = serviceAccessToken();

// 参加者かどうか。これが無いと、アカウントさえあれば誰でもどの会話にも push できる。
$conversation = firestoreGet("conversations/$conversationId", $accessToken);
if ($conversation === null) fail(404, 'no_conversation');

$memberIds = fieldStringArray($conversation, 'memberIds');
if (!in_array($senderUid, $memberIds, true)) fail(403, 'not_a_member');

$recipients = array_values(array_filter($memberIds, static fn ($id) => $id !== $senderUid));
if ($recipients === []) { echo json_encode(['sent' => 0]); exit; }

$isGroup = fieldString($conversation, 'type') === 'group';
$title   = $isGroup
    ? ((fieldString($conversation, 'title') ?: 'グループ'))
    : ($senderName !== '' ? $senderName : '新しいメッセージ');

$sent = 0;
$stale = [];

foreach ($recipients as $uid) {
    // 自分の画面に何が出るかを決めるのは受け取る側で、送る側ではない。
    $settings = firestoreGet("users/$uid/settings/app", $accessToken);
    $showBody = true;
    if (is_array($settings) && isset($settings['fields']['notificationShowsBody']['booleanValue'])) {
        $showBody = (bool) $settings['fields']['notificationShowsBody']['booleanValue'];
    }

    $devices = firestoreGet("users/$uid/devices", $accessToken);
    foreach ($devices['documents'] ?? [] as $device) {
        $fcmToken = $device['fields']['fcmToken']['stringValue'] ?? null;
        if ($fcmToken === null) continue;

        $visibleBody = $showBody
            ? mb_substr($body, 0, MAX_BODY_CHARS)
            : 'メッセージが届きました';

        [$status, $response] = httpPostJson(FCM_ENDPOINT, [
            'message' => [
                'token' => $fcmToken,
                'data'  => [
                    'type'           => $type,
                    'callId'         => $callId,
                    'conversationId' => $conversationId,
                    'senderName'     => $senderName,
                    'title'          => $title,
                    'body'           => $visibleBody,
                ],
                // 着信は Doze を突き抜けないと着信にならない。同時に期限も要る。
                // 発信側があきらめた30秒後に鳴り始める端末は、遅れた通知ではなく
                // 間違った通知。
                'android' => $type === 'call'
                    ? ['priority' => 'high', 'ttl' => '30s']
                    : ['priority' => 'high'],
            ],
        ], ["Authorization: Bearer $accessToken"]);

        if ($status >= 200 && $status < 300) {
            $sent++;
        } elseif ($status === 404 || $status === 400) {
            // アプリが消されたか、トークンが入れ替わった。
            //
            // ここでの削除だけを許す理由。
            //
            // この企画の他のどこでも、サーバーが誰かのデータを消すことは許していないし、
            // その原則は保つ価値がある。ここが例外なのは2つの理由から。拒否された
            // トークンは人についてのデータではなく、FCM がいま「違う」と言った宛先
            // そのものであること。そして、他に片付けられるのは、もう存在しない端末
            // だけであること。放っておくと溜まり、送信のたびに届かない要求の費用を払い、
            // 返事は延々と「sent 1, stale 1」になる。それは配達の不具合のように読め、
            // 本物の不具合を隠す。
            //
            // **消しているのは宛先であって、メッセージではない。** このサーバーが
            // 会話の中身に触れないという禁止は、そのまま。
            $reference = $device['name'] ?? '';
            $deleted = false;
            if ($reference !== '') {
                $deleted = httpDelete(
                    'https://firestore.googleapis.com/v1/' . $reference,
                    ["Authorization: Bearer $accessToken"]
                );
            }
            $stale[] = [
                'uid'     => $uid,
                'device'  => basename($reference),
                'removed' => $deleted,
            ];
        }
    }
}

echo json_encode(['sent' => $sent, 'stale' => $stale]);
