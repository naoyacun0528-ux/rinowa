# Rinowa Direct Network — Yosuga v1

**設計。** 名前の一覧は `NAMES.md`。

> **Cloud when far. Direct when near.**

近くにいる Rinowa ユーザー同士は、可能ならサーバーを経由せず端末間で直接つながる。
遠ければ、これまで通りクラウドを通る。**利用者はその違いを操作しない。**

このドキュメントは [PRIVACY_PRINCIPLES.md](PRIVACY_PRINCIPLES.md) に従属する。
脅威と漏洩については [DIRECT_THREAT_MODEL.md](DIRECT_THREAT_MODEL.md) を参照。

**現時点では設計のみ。実装しない。**
Cloud Messaging は一切変更しない。Mesh は作らない。独自暗号は作らない。

---

## 0. 最初に結論 — 調査で分かった制約

設計より先に、**やりたいことの一部は現在の OS では実現できない**ことが分かった。
隠さずここに置く。

| やりたいこと | 現状 | 根拠 |
|---|---|---|
| Android ↔ Android を**完全オフライン**で | **可能** | Nearby Connections が Wi-Fi Direct / BLE 等を使える |
| iPhone ↔ iPhone を完全オフラインで | **可能** | Multipeer Connectivity（Apple 間のみ） |
| Android ↔ iPhone を**同じ Wi-Fi 上で** | **可能** | Nearby Connections の iOS SDK は Wi-Fi LAN medium のみ対応 |
| Android ↔ iPhone を**完全オフラインで** | **現時点で不可能** | iOS 側に BLE medium が無い（Google が開発中と表明） |
| バックグラウンドの iPhone を Android から BLE で発見 | **不可能** | iOS はバックグラウンド時、service UUID を非公開の "overflow area" に入れる。**この領域は Apple 製端末しか解釈できない** |

### これが意味すること

**構想13（Android ↔ iPhone）は「半分」達成できる。**

- 家・職場・学校のように**両者が同じ Wi-Fi にいる**なら、Android ↔ iPhone の Direct は成立する。
  これは「近くにいる」場面のかなりの割合を占める。
- 一方、**災害・圏外・回線混雑という構想29の場面では、Android ↔ iPhone は成立しない。**
  そこで動くのは Android ↔ Android と iPhone ↔ iPhone だけになる。

無理な技術ハックで埋めない。iOS の overflow area は**仕様が公開されていない**領域であり、
逆解析して依存する実装は OS 更新で壊れるうえ、やってよいことでもない。

Google は iOS 向け BLE medium を作業中と述べている。**それが出たら再評価する。**
Rinowa 側は特定 API に決め打ちしない構造にしておく（§4）。

---

## 1. 何を最適化するのか

Direct は「P2P だからすごい」ではない。効く場面は限られていて、そこを狙う。

| 効くもの | 理由 |
|---|---|
| **Asset 転送**（カスタムスタンプ・画像・音声） | 一番大きい。クラウド往復が消える |
| 近距離の遅延 | サーバー往復が無くなる |
| 転送量 | 相手が隣にいるのにデータセンターを往復する必然性が無い |
| オフライン | 圏外でも隣の友達には届く |

**効かないもの**もはっきりさせる。

- テキスト1通は数百バイト。クラウドでも十分速く、Direct にしても体感は変わらない
- 相手がアプリを閉じていれば Direct は成立しない。**通知は結局クラウドが要る**
- 発見と接続の確立には時間がかかる。1通送るためだけに接続を張るのは損

> **だから Direct は「速いから使う」のではなく、「既に繋がっているなら使う」。**
> 接続確立は会話を開いた時や近接検出時に先行して行い、送信の瞬間には既に張れている状態を目指す。

---

## 2. 絶対に守ること

### 2.1 Cloud の信頼性を犠牲にしない

**Direct に依存する Core Functionality を作らない。**
Direct を OFF にしても、Rinowa は完全なメッセンジャーとして成立する。

Direct は Optional Optimization である。この一文が守られなくなる設計は却下する。

### 2.2 毎回の確認を要求しない

**禁止する UX:**

- 送信のたびの4桁コード
- 毎回の PIN 確認
- 毎回の QR 読み取り
- 毎回の「接続しますか？」

これらを要求する設計は、実用性が無いという理由で却下する。
既に Rinowa 上で友達であり、相手端末が検証済みなら、**黙って繋ぐ**。

許容される唯一の確認は §6.3 の「相手の新しい端末を初めて見たとき、一度だけ」。

### 2.3 近くにいるから信用する、をやらない

近接は認証ではない。§6 の暗号学的検証を通らない相手とは繋がない。

### 2.4 独自暗号を作らない

標準プリミティブ・OS の Key Store・検証済みプロトコルのみ。
[PRIVACY_PRINCIPLES.md](PRIVACY_PRINCIPLES.md) の禁止事項をそのまま引き継ぐ。

---

## 3. 全体像

```
                  ┌──────────────────────────────┐
                  │        ChatViewModel         │
                  │   send(message) — 経路を知らない │
                  └───────────────┬──────────────┘
                                  │
                  ┌───────────────▼──────────────┐
                  │        MessageRouter         │   ← 経路を決める唯一の場所
                  │  「今 Direct が張れているか」   │
                  └───────┬──────────────┬───────┘
                          │              │
              ┌───────────▼──┐      ┌────▼──────────────┐
              │ DirectTransport│      │  CloudTransport   │
              │ (認証済み Peer) │      │  (Firestore)      │
              └───────┬───────┘      └────┬──────────────┘
                      │                   │
              ┌───────▼───────┐           │
              │ Platform impl │           │
              │ Android / iOS │           │
              └───────────────┘           │
                                          │
                              ┌───────────▼───────────┐
                              │  同じ Message Model    │
                              │  同じ messageId        │
                              └───────────────────────┘
```

**上の層は経路を知らない。** `ChatViewModel` は今日と同じ `send()` を呼ぶ。

---

## 4. Transport 抽象

特定 API に決め打ちしない。§0 の通り、使える技術は OS 更新で変わる。

```kotlin
/** 端末間の直接経路。実装は OS ごと、選択は RinowaDirect が行う。 */
interface DirectTransport {

    val capabilities: TransportCapabilities

    /** 発見。友達だけが対象 — §7 の rotating identifier を使う。 */
    fun discoverPeers(): Flow<DiscoveredPeer>
    fun stopDiscovery()

    /** 接続。まだ認証されていない。 */
    suspend fun connect(peer: DiscoveredPeer): Result<PeerLink>

    /** §6 の Challenge-Response。ここを通るまで payload を流さない。 */
    suspend fun authenticate(link: PeerLink): Result<AuthenticatedPeer>

    suspend fun send(peer: AuthenticatedPeer, payload: DirectPayload): Result<Unit>
    fun receive(): Flow<DirectPayload>

    suspend fun disconnect(peer: AuthenticatedPeer)
    val connectionState: StateFlow<Map<PeerId, DirectConnectionState>>
}

/** 何が得意な経路かを Router が知るための情報。 */
data class TransportCapabilities(
    val worksOffline: Boolean,      // インターネット無しで成立するか
    val crossPlatform: Boolean,     // Android ↔ iOS が通るか
    val approxThroughput: Throughput,  // Text / Asset どちらに向くか
    val backgroundCapable: Boolean,
)
```

`connect` と `authenticate` を**分けている**のが要点である。
繋がったことと、相手が誰か確かめたことは、別の出来事として扱う。

### 4.1 実装候補（決め打ちしない）

**Android** — 実装時点で再確認する。

| 候補 | オフライン | Android↔iOS | 備考 |
|---|---|---|---|
| **Nearby Connections** | ○ | △（同一 Wi-Fi のみ） | 有力。ただし**2026年後半から Wi-Fi/BT の自動 ON が廃止**され、アプリが状態を確認して利用者に依頼する必要がある |
| Wi-Fi Aware (NAN) | ○ | × | API 26+ だが `FEATURE_WIFI_AWARE` 必須で**端末が限られる**。メッセージは約255バイト上限、実転送は別途ネットワーク確立が要る |
| Wi-Fi Direct | ○ | × | Android のみ |
| BLE (GATT) | ○ | △ | 唯一 iOS と話せる可能性があるが低速。§0 のバックグラウンド制約あり |
| NSD / mDNS | ×（LAN 必要） | ○ | 同一 LAN 限定だが**素直に cross-platform** |

**iOS** — 実装時点で再確認する。deprecated API を新規設計の中心にしない。

| 候補 | 備考 |
|---|---|
| **Network.framework**（NWBrowser / NWListener + Bonjour） | 現行。Local Network 権限が要る |
| Multipeer Connectivity | 非 deprecated。ただし **Apple 製端末間のみ** |
| Core Bluetooth | バックグラウンドの制約が §0 の通り厳しい |

### 4.2 選択方針

**1つに決めない。複数を持ち、状況で選ぶ。**

```
相手が Android かつ 圏外          → Nearby Connections (offline medium)
相手が iPhone  かつ 同一 LAN      → Nearby Connections (Wi-Fi LAN) または mDNS
相手が iPhone  かつ 圏外          → Direct 不可 → Cloud（Cloud も不可なら送信保留）
```

`TransportCapabilities` はこの判断のために存在する。

---

## 5. Identity と Trusted Device

### 5.1 端末は固有の鍵を持つ

```
Rinowa Account (uid)
   └── Device Identity
         ├── deviceId
         ├── public key      ← Cloud に登録する
         ├── private key     ← 端末から出さない
         ├── metadata (OS / model category / 表示名)
         ├── createdAt
         └── lastSeen
```

**秘密鍵は端末外へ出さない。** OS の安全な保管機構に置く。

- Android: Android Keystore（ハードウェア支援の有無を確認し、可能なら StrongBox）
- iOS: Keychain / Secure Enclave

具体的 API は実装時点の公式資料で確認する。

### 5.2 Cloud の役割

Cloud は **Identity の配布だけ**を行う。

> 「この public key は Rinowa ユーザー B の登録端末のものである」

を答える。**Direct 通信の payload は一切通らない。**
これが Direct の意味そのものなので、ここを曖昧にしない。

```
users/{uid}/devices/{deviceId}
  publicKey    : string
  platform     : "android" | "ios"
  label        : string        ← 利用者が見て分かる名前
  createdAt    : timestamp
  lastSeenAt   : timestamp
  revokedAt    : timestamp?    ← §5.4
```

読める相手は**友達に限る**。誰でも読めると「この人は何台持っているか」が漏れる。

### 5.3 Account-centric は維持する

> **Device can be replaced. Account persists.**

[SYNC_AND_BACKUP.md](SYNC_AND_BACKUP.md) の原則は変わらない。
端末を替えたら新しい Device Identity を登録し、古いものを消す。
**Trusted Device 一覧は Account 設定から見られ、削除できる**（将来 UI）。

### 5.4 失効

紛失した端末は Cloud から revoke できる。revoke された端末は、
新しい Direct 接続の相手として認証を通らない。

> **穴を明記する。** revoke 情報をまだ受け取っていないオフライン端末は、
> 失効した端末を信用し続ける。これは設計上の既知の弱点であり、
> [DIRECT_THREAT_MODEL.md](DIRECT_THREAT_MODEL.md) に記載する。
> 「オフラインでも動く」ことと「失効が即座に効く」ことは両立しない。

---

## 6. 自動認証

### 6.1 手順

```
A が B らしき端末を発見
   ↓
A → B : random challenge (nonce)
   ↓
B     : 自分の秘密鍵で署名
   ↓
B → A : signature + deviceId
   ↓
A     : Cloud から取得済みの B の public key で検証
   ↓
逆向きに同じことを行う（相互認証）
   ↓
検証成功 → Direct Channel 確立
```

**独自プロトコルを発明しない。** 標準的な Challenge-Response と標準署名アルゴリズムを使い、
可能なら OS 提供の secure transport（TLS 相当）にこの identity を載せる形にする。

### 6.2 利用者は何もしない

上記は全て自動。画面には何も出ない。
成立したら送信状態に小さく `⚡ Direct` と出るだけ。

### 6.3 一度だけの例外

友達 B が**新しい端末を追加した**とき、その端末と初めて Direct 接続する際に限り、

> 「B の新しい端末（Pixel 10）と直接つながることを許可しますか？」

を**一度だけ**出すことを許容する。以後は出さない。

これも UX 検証の対象であり、不要と判断されれば消す。
判断材料は「新しい端末の追加頻度」と「利用者がこの確認を理解できたか」。

---

## 7. 発見時のプライバシー

**近くにいる Rinowa ユーザーの一覧を、他人が収集できる設計にしない。**

### やらないこと

Nearby の広告に平文で載せてはいけないもの:

- 表示名 / メールアドレス / uid / 招待コード
- 固定の deviceId
- プロフィール画像

### やること

- **rotating identifier** — 一定時間で変わる短い識別子を広告する
- **friend matching** — 友達だけが「これは誰か」を復元できる形にする
  （例: 共有秘密から導出した回転トークン。具体方式は実装時に標準構成から選ぶ）
- **authenticated reveal** — 相手が誰かを知るのは §6 の認証を通ったあと

### 知らない人

知らない Rinowa ユーザーが近くにいても、**勝手に接続・Identity 交換・データ送受信をしない。**
基準は Friend / Trusted Relationship のみ。

将来 Nearby Friend Request を作る場合は**別設計**として扱う。ここには混ぜない。

---

## 8. Routing と Fallback

### 8.1 経路選択

```kotlin
suspend fun send(message: Message) {
    val route = when {
        directChannelAuthenticated(message.conversation) -> Route.Direct
        else -> Route.Cloud
    }
    ...
}
```

**利用者に選ばせない。** 送信画面に経路の選択肢は置かない。

### 8.2 Fallback

Direct は落ちる。以下は全部起こる前提で設計する。

- 相手が歩いて離れた
- 画面がロックされた / アプリがバックグラウンドへ行った
- プロセスが殺された
- Wi-Fi / Bluetooth が切られた
- 接続はできたが ack が返らない

```
Direct 送信
   ↓ 一定時間内に ack が無い / 接続が切れた
Cloud へ fallback
   ↓
配信
```

**Direct の失敗が、メッセージの消失になってはいけない。**
これが §2.1 の具体形である。

### 8.3 二重配信を防ぐ

Direct で送った直後に Cloud へ fallback すると、**相手に2通届きうる。**

対策は **messageId をクライアントが決める**こと。

```
送信側: messageId をクライアントで採番 → Direct と Cloud のどちらで送っても同じ id
受信側: 既に持っている messageId は捨てる（idempotent）
```

> **この1点だけは、今のコードにも影響する。**
> 現在 `MessageRepository.send()` は Firestore の `add()` を使っており、
> **id はサーバーが決めている**。つまりメッセージはクラウドへ届くまで id を持てない。
> Direct を後から足すとき、ここが構造的な障害になる。
> → §12 で、この1点だけを先に直す。

### 8.4 順序

Direct と Cloud が混ざっても会話順が壊れないこと。

Prototype 段階では**過剰な分散システム設計をしない。**

- 送信側の local timestamp を message に持たせる
- Cloud 経由のものは server timestamp も持つ
- 表示順は「server timestamp があればそれ、無ければ local timestamp」

これで足りない事例が出てから、logical clock 等を検討する。
先に作らない。

---

## 9. Asset 転送 — Direct が一番効くところ

[STICKER_ARCHITECTURE.md](STICKER_ARCHITECTURE.md) の4原則は**変えない**。

```
PROCESS ON DEVICE / STORE ONCE / REFERENCE BY ID / CACHE LOCALLY
```

Direct が足すのは、**MISS のときの取得先が1つ増える**ことだけである。

### 9.1 AssetResolver

```
stickerId を描画したい
   ↓
1. Local Store            → HIT なら即描画（今と同じ）
   ↓ MISS
2. Direct Peer            → 近くにその Asset を持つ認証済み Peer がいるか
   ↓ 不可
3. Cloud                  → 今と同じ経路
```

```kotlin
interface AssetSource {
    val priority: Int
    suspend fun fetch(id: StickerId): Result<ByteArray>
}
```

`LocalStickerStore` は既に「MISS なら取りに行く」構造になっている。
**取りに行く先を差し替えられるようにするだけ**で、呼び出し側は変わらない。
ID 経由にした意味がここでも効く。

### 9.2 Cloud Master は維持する

Direct が使えても、**Master Asset は Cloud に置き続ける。**

理由は Direct では代替できないから:

- 機種変更 / 再インストール
- バックアップ
- 相手が遠距離
- Direct 不可の組み合わせ（§0）
- オフライン復旧

**Direct は Cloud Backup を置き換えない。** ここは譲らない。

---

## 10. Cloud History との整合

Direct で送ったメッセージを、利用者のクラウド履歴へ同期するか。

**今は決めない。** 将来の Message E2EE と絡むため、軽率に確定させない。
選択肢だけ並べて比較対象として残す。

| 案 | 内容 | 長所 | 短所 |
|---|---|---|---|
| **A** | Direct 配信 + 常に Cloud にも履歴を書く | 履歴が常に揃う | **Direct の意味が半分消える**（本文が結局クラウドへ行く） |
| **B** | オフライン時のみ Direct-only、後で暗号化同期 | Direct の意味が残る | オンライン復帰まで他端末に出ない |
| **C** | 本文は Direct のみ、メタデータだけ Cloud 同期 | 転送量最小 | 履歴の完全性が落ちる |

E2EE が入ると B と C の差が小さくなる（どちらもクラウドは中身を読めない）。
**E2EE の設計が決まってから選ぶ。**

---

## 11. Transport Encryption と Message E2EE を混同しない

```
┌─────────────────────────────────────┐
│  Message E2EE（将来）                 │  ← 経路が変わっても同じ暗号文
├─────────────────────────────────────┤
│  Transport Encryption                │  ← Direct Channel / TLS
├─────────────────────────────────────┤
│  Direct Transport / Cloud Transport  │
└─────────────────────────────────────┘
```

- **Direct Channel の暗号化は必須。** ただし標準 TLS 相当 / OS 提供の secure transport / 検証済み library を使う
- **将来 E2EE を入れたら、Cloud 経路でも Direct 経路でも同じ暗号化 payload を運ぶ**
- **経路によってメッセージのセキュリティモデルが変わらない**ことを目標にする

「Direct だから安全」は誤り。Transport が暗号化されていることと、
サーバーが中身を読めないことは別の話である。

---

## 12. 今のコードに入れる唯一の変更

構想40の但し書き（「将来 Direct 対応を妨げる構造になっている場合のみ、
過剰にならない範囲で抽象化してよい」）に該当するのは **1箇所だけ**である。

**messageId をクライアントが決めるようにする。**

- 現状: `messages.add(payload)` → id は Firestore が採番
- 変更後: `messages.document()` で id を先に取り、`set(payload)` で書く

コストはゼロ（同じ1回の書き込み）。得られるもの:

- メッセージがクラウドへ届く前から id を持つ
- Direct と Cloud で同じ id を使える
- 二重配信を受信側で捨てられる（§8.3）

**Transport 抽象も Router もまだ作らない。** 実装が無いのに層だけあるのは、
Prototype 0 の「不要な複雑性を足さない」に反する。

---

## 13. Haptics

[HAPTIC_DESIGN.md](HAPTIC_DESIGN.md) に統合する。原則は「意味と一致させる」こと。

| 場面 | 方針 |
|---|---|
| 通常の送信 | **Direct でも Cloud でも同じ `Send`** |
| Direct 接続が成立した | ごく軽い1回。`ReadReceipt` と同格かそれ以下 |
| Direct 転送の完了 | 原則なし。必要な場合のみ |

**送信の触覚を経路で変えない。** 利用者にとって「送った」は1つの出来事であり、
裏の経路が違うだけで手応えが変わるのは、意味と感触の対応を壊す。

---

## 14. UI

原則、利用者は Direct を意識しなくてよい。

- 送信状態に小さく `⚡ Direct`
- 会話情報に接続状態
- 設定に **Rinowa Direct ON / OFF**（OFF でも完全動作、§2.1）

「今 Direct で送りますか？」のような**経路を選ばせる UI は作らない。**

---

## 14.5. オフライン時の通知 — 決定（2026-08-16）

**圏外では通知は届かない。** FCM はインターネットを要するので、これは回避できない。

Direct 経由で通知を出すことは技術的には可能だが、**Rinowa が生きている必要がある。**
FCM が強いのは OS が受け取ってアプリを起こす点で、Direct にその仕組みは無い。

| Rinowa の状態 | Direct 経由の通知 |
|---|---|
| 開いている / 生きている | 出せる |
| スワイプ終了・OS に殺された | **出せない** |

3行目を埋める唯一の方法はフォアグラウンドサービスだが、代償は
**消えない常駐通知**と**継続的な電池消費**であり、§15 と §2.1 に正面から衝突する。

### 決定: **B（アプリが生きている間だけ）**

常駐サービスは作らない。理由:

- **電池の実測値が無い。** 代償が数字で分かっていない段階で、Direct を使わない人にまで
  常駐通知と電池消費を負わせる判断はできない。→ Direct-1 で測る
- **後から足せる。** 「常に待ち受ける（災害モード）」を1段追加するだけで、
  設計をやり直す必要が無い

### A を選ぶ理由（将来の再検討用）

**Direct をやる動機そのもの——災害・圏外（構想29）——は B では埋まらない。**
端末がポケットにあり、Rinowa を閉じていて、回線が死んでいる場面で B は何も届けない。

つまり B → A は**順序**であって択一ではない。Direct-1 の電池測定が出た時点で、
「常に待ち受ける」を任意の3段目として足すかを判断する。既定にはしない。

なお通知を出す仕組み自体は既にある（`RinowaMessagingService.show()` はどこからでも
呼べる）。Direct-2 のルーティングができれば、通知を出すのは数行で足りる。

## 15. Battery

**常時 Discovery は禁止。**

Discovery を行ってよい条件を絞る:

- アプリが前面にある
- 会話を開いている
- 最近やり取りした相手が近くにいる可能性がある
- Direct が ON
- 充電中

Battery Cost は §17 の主要評価項目に入れる。
「速いが電池を食う」は Rinowa では失格である。

---

## 16. Permissions

Nearby / Bluetooth / Wi-Fi 系の権限を**初回起動でまとめて要求しない。**

Rinowa Direct を初めて使う場面で、理由が分かる形で求める（Contextual Permission Request）。
**拒否されても Cloud Messaging は完全に動く。**

Android 13+ では `NEARBY_WIFI_DEVICES`（`neverForLocation` 付き）が要る。
iOS では Local Network 権限が要る。実装時点の公式資料で確認する。

なお **2026年後半から Nearby Connections は Wi-Fi / Bluetooth を自動で ON にしなくなる**。
無線が切れている場合、アプリが検知して利用者に依頼する必要がある。
これは §2.2 の「毎回確認しない」と衝突しうるので、
**「無線が切れているときだけ、一度だけ」**に留める設計が要る。

> ### 実機で観測（2026-08-16）
>
> Pixel 10 と arrows We2 で検証中、**Wi-Fi を手動で切った後に両端末とも勝手に Wi-Fi が
> 復帰した**。その直後、切断中に送ったつもりのメッセージが届いた。
>
> つまり **Nearby は今もまだ無線を自動で ON にしている。** そして届いたのは
> オフラインで届いたのではなく、**Wi-Fi が戻って接続が張り直された結果**である。
>
> これは2つのことを意味する。
>
> 1. **この挙動に依存した「オフラインで動いた」という結論は誤りになる。**
>    廃止後、同じ手順は無音で失敗する
> 2. **オフラインの検証は、無線の自動復帰を止めた状態で行う必要がある。**
>    Android の「Wi-Fi を自動的に ON にする」を切る、または機内モード＋Bluetooth 手動 ON
>
> Direct-1 の測定では、**接続がどの無線に載っているかを必ず記録する**こと。
> Nearby は経路名を返さないが、`onBandwidthChanged` の quality が代わりになる
> （`HIGH` = Wi-Fi、`LOW` = Bluetooth）。同じ Wi-Fi にいる限り Nearby は速い経路を選ぶので、
> **経路を見ずに「Nearby で繋がった」と言うと、実質 LAN 階層を測っていることになる。**

---

## 17. Prototype で測る数値

Direct を採用するかどうかは、感想ではなく数字で決める。

| 指標 | なぜ測るか |
|---|---|
| discovery latency | 「近くにいる」と気づくまで |
| connection latency | 送信の瞬間に間に合うか |
| message latency | Cloud と比べて本当に速いのか |
| throughput | Asset 転送に使えるか |
| reconnection time | 離れて戻ったとき |
| **battery usage** | §15。失格条件になりうる |
| connection success rate | 成立しないなら意味がない |
| message loss | Cloud より落ちるなら使えない |
| **fallback success rate** | §2.1 の実測 |
| **duplicate message rate** | §8.3 が効いているか |

**すべて Cloud 経由と比較する。** 単独の数字には意味がない。

---

## 18. Telemetry

[ANALYTICS_SCHEMA.md](ANALYTICS_SCHEMA.md) の規則をそのまま適用する。

送ってよい:

- Direct 接続の試行回数 / 成功率
- 平均遅延
- 転送バイト数 / 回避できた Cloud 転送量
- fallback 率
- 失敗理由の**分類**（enum）
- OS / version / device capability

**送らない:**

- 本文・画像の中身
- 誰と繋がったか（相手の識別子）
- 位置

「誰が誰の近くにいたか」は、Direct が新たに作り出す**最も危険な情報**である。
Analytics へは絶対に入れない。→ [DIRECT_THREAT_MODEL.md](DIRECT_THREAT_MODEL.md)

将来、利用者本人にだけ見せる統計は検討してよい。

```
今日: Direct 43% / Cloud 57%
Direct で送った容量: 128 MB
節約できたクラウド転送: 128 MB
```

---

## 19. 段階

[ROADMAP.md](ROADMAP.md) の Direct-0 〜 Direct-3 を参照。

| | 内容 |
|---|---|
| **Direct-0** | 設計・脅威モデル・抽象定義・文書（**このドキュメント。実装しない**） |
| **Direct-1** | Android 2台、Cloud を使わず "Hello" を送る最小検証。Developer Test Screen で可 |
| **Direct-2** | 自動発見・Identity 検証・Asset 転送・Cloud fallback |
| **Direct-3** | iOS、および Android ↔ iPhone（§0 の制約の範囲で） |

**Mesh は実装しない。** ROADMAP の Research 項目に留める（§20）。

---

## 20. Mesh は研究項目

`A ↔ B ↔ C` で B を経由して A → C へ届ける構造は魅力的だが、
routing / privacy / encryption / battery / abuse / duplicate delivery /
malicious relay / storage / consent が同時に複雑になる。

**今は作らない。** ROADMAP の Research に置く。

特に **consent** — 自分の端末が他人のメッセージを中継することに、
利用者が同意しているか。これを曖昧にしたまま作ってはいけない。
