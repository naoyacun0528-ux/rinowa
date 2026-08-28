# ROADMAP

いきなり LINE 代替を完成させない。段階ごとに「検証したいこと」を1つに絞る。

---

## 現在地（2026-08-26）

| 段 | 状態 |
| --- | --- |
| Prototype 0 — 操作感 | **完了**（2026-08-15 判定） |
| Prototype 1 — バックエンド接続 | **完了**。残るは Crashlytics の確認1件と Custom Sticker Composer |
| **RINOWA SIGIL**（封の仕組み） | **本体・鍵の検証とも完了**（V-1/2/3/5）。残るは V-4（クロスサイニング） |
| Prototype 2 — 運用に耐える形へ | 一部先行して着手済み（下記） |
| Public Beta 前 | 12項目中1つ（鍵の検証を追加したため分母が増えた） |

**この表と各段の記述が食い違ったら、各段のほうが正しい。** 表は目次であって記録ではない。

---

## Prototype 0 — 操作感の検証（**完了**）

**検証したいこと: 「操作感が LINE 以上に気持ちいいか」だけ。**

ネットワーク通信は最低限または不要。ローカルのみ。Android のみ。

### 実装範囲

- [x] Haptic Design System（意味ベース API + 段階的フォールバック）
- [x] **Haptic Lab**（全触覚トークンを実機で個別に試すデバッグ画面）
- [x] Chat list
- [x] Chat screen
- [x] Message bubble
- [x] Composer
- [x] Send（送信アニメーション）
- [x] Reply swipe（threshold 到達時の触覚）
- [x] Long press
- [x] Reactions（リアクションピッカー）
- [x] Basic animations / スクロール挙動
- [x] Light / Dark（**ライト先行、ダーク対応**）
- [x] **Sticker ID / Asset モデルの抽象化**（メッセージに画像を埋め込まない形を確立）
- [x] Local Sticker Store（`filesDir`、HIT/MISS）
- [x] 組み込みサンプルスタンプ / Sticker ボタン / Sticker picker / 送信と描画
- [ ] Custom Sticker Composer — **UX の初期検討のみ。実装は P1**

**実装範囲は埋まった。ただしこれは完了条件ではない。**

### 完了条件の判定

**2026-08-15、開発者本人が実機で判定し、6項目すべて合格。**

### 残っている作業

- [ ] Canvas アイコンに `contentDescription` がない（TalkBack 未対応。P1 でまとめて）

**Prototype 0 の検証は完了。** 以降の作業は Prototype 1 と視覚フェーズへ移る。

### 決着した項目

- 送信側バブルが右端からはみ出す → **仕様として採用**（変更しない）
- リアクションは**1人1つ**。2つ目を選んだら1つ目を取り消して差し替える
- **触覚**（2026-08-15 判定） — 周波数の引き上げで OK。
  `send` / `threshold` はプリミティブ（T3）のまま確定
- **リアクションの差し替え**、**送信ボタンの弾み**、**浮いた面の見え方** — すべて OK

### 実装しないもの

- アカウント / ログイン
- サーバー通信 / 同期
- プッシュ通知
- 画像送信（UI の器のみ、実際の選択は P1）
- Analytics の実送信（API と no-op 実装のみ用意する）

### 完了条件

[PRODUCT_VISION.md](PRODUCT_VISION.md#prototype-0-の合格条件) の合格条件を満たすこと。
**実機で開発者本人が触って判断する。** 実装完了は完了条件ではない。

---

## Prototype 1 — バックエンド接続（**完了**）

Prototype 0 の操作感が確認できた **後** に着手する。**少人数の友人限定。**

**9項目すべて実装済み。** 必須確認4件のうち3件が済み、残りは Crashlytics（未導入）。
「あわせて」の項目では Custom Sticker Composer と Presence、Developer Console が未着手。

### バックエンド候補

Firebase Authentication / Cloud Firestore / Cloud Functions / Cloud Storage /
Firebase Cloud Messaging / Crashlytics / Analytics / App Check

### 実装範囲

優先順:

1. Authentication
2. Account データモデル
3. 実メッセージング（Direct / Group / Read state）
4. **Sticker Master Asset**（Cloud Storage）
5. **Local Sticker Store の MISS 経路を実接続**
6. **ID ベースのスタンプメッセージ**
7. **再インストール／ログイン後の復元** → [SYNC_AND_BACKUP.md](SYNC_AND_BACKUP.md)
8. Analytics（[ANALYTICS_SCHEMA.md](ANALYTICS_SCHEMA.md) に従う）
9. Feedback / Feature voting

あわせて:

- **Custom Sticker Composer**（写真 → クロップ → 背景除去 → 文字 → 保存。**すべて端末内**）
- Profile / Image send / Push notification / Basic presence
- Privacy → Analytics Data 画面
- **Account & Cloud 画面**（何を預かっているかを見せる）
- Developer Console（集計のみ。**本文表示なし**）

### この段階で守ること

- **元写真をアップロードしない。** 完成したスタンプだけを送る
- **同じスタンプを送るたびに画像を上げない。** Master Asset は1つ
- **表示のたびにダウンロードしない。** 一度取得したら端末に持つ
- **「Firebase にあるから使う」で採用しない。** 無いと何ができないかを言えるものだけ使う

### この段階の必須確認

**主張ではなく、実行できる形で確かめること。** ルールは「正しく見える」ことがあり、
それは証拠ではない。

- [x] Firestore Security Rules で、会話参加者以外が本文を読めないこと
      → `rules-tests/run.js`（エミュレータ実行、26件）
- [x] **管理者アカウントに本文への特権的読み取り経路が存在しないこと**
      → 同上。`admin: true` / `staff: true` を主張するトークンでも読めないことを検証
- [x] Analytics のイベントに `String` パラメータが1つもないこと
      → `core/analytics` の `AnalyticsSchemaTest`（型で保証、テストで再確認）
- [ ] Crashlytics のカスタムキー・ログに本文が入らないこと
      → Crashlytics 自体が**未導入**。導入時に確認する

```bash
cd rules-tests && npm install && npm test
```

### 進捗（0.5.0 時点の記録。以降の変化は下の「暗号化フェーズ」へ）

| # | 項目 | 状態 |
|---|---|---|
| 1 | Authentication | 完了（Google / メール+確認 / 削除 / 再認証） |
| 2 | Account データモデル | 完了（`users` / `inviteCodes` / 設定 / スタンプ参照） |
| 3 | 実メッセージング | Direct と既読は完了・**実機2台で検証済み**。Group は器のみで UI 未実装 |
| 4 | Sticker Master Asset | 完了。ただし **Cloud Storage ではなく Firestore Blob**（下記） |
| 5 | Local Sticker Store の MISS 経路 | 完了（1度だけ取得・ハッシュ検証・端末保持） |
| 6 | IDベースのスタンプメッセージ | 完了 |
| 7 | 再インストール／ログイン後の復元 | 完了（会話・設定・スタンプ参照。画像は必要時に取得） |
| 8 | Analytics | 完了（`FirebaseAnalyticsSink`、opt-out 画面つき） |
| 9 | Feedback / Feature voting | 完了（投稿・一覧・投票・取り下げ） |

**2026-08-15、実機2台・2アカウントで送受信を確認した。**
Pixel 10（高畑直也）と arrows We2（Next Lab.）の間で、テキストの往復と
スタンプの受信描画が両側で正しく鏡になることを確認。3 と 6 は検証済み。

**5（MISS 経路）はまだ実地で通っていない。** 組み込みスタンプは APK に同梱されるので、
どちらの端末も最初から持っている。MISS が起きるのは自作スタンプを送ったときで、
それには Custom Sticker Composer が要る。

#### 4 について: Cloud Storage を使っていない

正本の画像は Firestore のドキュメントに `bytes`（Blob）として置いている。理由:

- スタンプは `StickerLimits.MAX_BYTES` = 200KB 上限。Firestore の 1MB 制限に収まる。
  **Cloud Storage が解決する問題（ドキュメントに入らない大きさ）が発生しない。**
- Cloud Storage は Blaze プラン必須。200KB を置くために課金アカウントを持つ判断は、
  友人数人のプロトタイプでは早い。
- サービスが1つ減れば、守るべき権限ルールも1つ減る。

**アーキテクチャは変えていない。** メッセージは `StickerId` だけを運び、正本は1つ、
端末は1度だけ取得して持つ。Cloud Storage へ移すときは `StickerRepository` の中身が
入れ替わるだけで、他はどこも触らない。ID 経由にした意味はここにある。

対価: CDN が無く、取得が1ドキュメント読み取りになる。1端末につき1回なので許容する。

### 「あわせて」の項目

**この節は 0.13.0 で実態に合わせて書き直した。実装済みのものを未着手と書いていた。**

| 項目 | 状態 |
|---|---|
| Profile 編集 | **完了**（`ui/profile/ProfileScreen.kt`、写真クロップつき） |
| グループ作成 UI | **完了**（`ui/chatlist/NewGroupScreen.kt`） |
| 画像送信 | **完了**（WebP 2048px。オリジナル画質は未対応） |
| プッシュ通知 | **完了**（`push.php` + FCM） |
| 音声通話 / ビデオ通話 | **完了**（WebRTC + TURN、背景継続・PiP つき） |
| 通話履歴をスレッドに残す | 着手中 |
| 動画送信 / オリジナル画質 | 未対応。**保存先の制約を先に解く必要がある** |
| Custom Sticker Composer | 未着手。**スタンプ MISS 経路の実地検証がこれ待ち** |
| Presence | 未着手 |
| Developer Console | 未着手 |

---

## RINOWA SIGIL（封の仕組み・計画外・0.15〜0.19 で実施）

**Rinowa の封の仕組みには名前が付いた。** 適用範囲の正本は
[RINOWA_SIGIL.md](RINOWA_SIGIL.md)。**この段と食い違ったら、あちらが正しい。**

読みは**シジル**。sigil は「封印の紋章」で、1つ1つに刻むもの。Rinowa はメッセージ
1通ごとに封をするので、盾や結界のような「境界の内側は安全」の比喩は採らなかった。

**この段は当初の ROADMAP に無い。** Prototype 1 のあと、Public Beta 前の必須項目だった
「E2EE 方式検討」を先に片付けたところ、実装まで通ってしまったため独立した段にする。

### 方式

**独自暗号は設計しない**という原則どおり、既存の検証済みプロトコルを採用した。

| | |
| --- | --- |
| 採用 | Matrix の **Olm / Megolm**（`org.matrix.rustcomponents:crypto-android`） |
| 根拠 | [RESEARCH_E2EE.md](RESEARCH_E2EE.md) |
| 前方秘匿性 | あり（Olm は Double Ratchet、Megolm はセッション更新） |

### 済んだもの

- [x] **本文の封**（`data/MessageEnvelope.kt`）。封の単位は**メッセージの中身まるごと**。
      本文だけ暗号化して写真の ID・寸法・サムネイルが外に出ていれば、それは
      「写真の説明文だけ暗号化して写真は平文で送る」ことになる
- [x] **写真・動画の暗号化**（Tink の AES-GCM-HKDF-STREAMING、ファイルごとの乱数鍵、
      鍵は封筒の中）。**サーバーを運用している人間にも読めない** → `MEDIA_ARCHITECTURE.md` §11
- [x] **通話の SDP / ICE を封筒に入れる**。平文で置いていた間は、書き換えた者と
      暗号化された通話を確立できた。ICE には双方の IP も入っていた
- [x] **通話記録の封**（0.19.1。それ以前は発信者の端末にしか復号できない形で書かれていた）
- [x] **バックアップ**（Google ドライブ + 暗証番号。端末で暗号化してから置く）
- [x] **鍵の端末ごと分離**（0.19.0。それ以前は同一アカウントの2台目が1台目の鍵を上書きしていた）
- [x] **実機検証**: 消去 → 再ログイン → 履歴が全部読めない（E2EE が正しい）→ 復元で読める。
      消したばかりの端末から**先に**送って相手が復号できることまで確認（0.19.3）

### 残っているもの — **ここだけ LINE に負けている**

- [ ] **鍵の検証（相手が本人か確かめる手段）**
      → 下の「鍵の検証フェーズ」へ

### 分かったこと

**規則と E2EE は別の防御線で、片方が破れてももう片方が残る。**
0.19.0 で `firestore.rules` の穴（参加者を勝手に増減できた）を塞いだとき、
入れられた側が読めたのは暗号文だけだった。`historyVisibility = JOINED` により
参加前のセッション鍵は配られないため。**規則が破られた線を E2EE が押し留めていた。**

---

## 鍵の検証フェーズ（次にやる）— SIGIL の最後の穴

**目的は1つ。「サーバーに本文が無い」から「相手が本人だと確かめられる」へ進む。**

いまの Rinowa は `onlyAllowTrustedDevices = false` で、未検証の端末を許している。
理由はコードに書いたとおり「確認画面が無いので拒否すると全部拒否になる」。
これは記録済みの現在地であって、発見された欠陥ではない。

### なぜ急ぐか

**LINE はここができている。** 技術白書にこうある。

> The whole process is transparent to users. There is no trusted third party who verifies
> public keys. Nevertheless, there is a way for users to verify the recipient. LINE enables
> users to view the fingerprint of their and the recipient's public key.

サーバーが公開鍵を配り、既定では誰も検証しない——そこは同じ。だが**利用者が指紋を
目視照合する手段が用意されている**。Rinowa にはそれが無い。

**「LINE を超える安全性」を名乗るなら、追いつくべき唯一の点がこれ。**

### 段

- [x] **V-1 指紋の表示**（0.20.0）。自分と相手の公開鍵の指紋を4文字区切り・等幅で。
      **これで LINE と並んだ**
- [x] **V-2 端末一覧**（0.20.0 / 0.20.1）。相手の端末を全部出す。消去前の登録も隠さない。
      **0.20.1 で直した**: 一覧を更新するのは送信時だけだったので、確認画面が古い記録を
      見せていた。実機で「今日登録した端末が出ない」として出た。
      **確認の画面が古い事実を見せるのは、無いより悪い**
- [x] **V-3 相手の鍵が変わったときに知らせる**（0.20.1）。前に見た指紋を端末に覚え、
      増えた・変わった・消えたを出す。**実機未検証**（相手の端末を変える機会が要る）
- [ ] **V-4 クロスサイニング** — **着手しない。下記**
- [x] **V-5 確かめた印**（0.20.1）。読み合わせた人が自分の端末に記録する。取り消せる。
      サーバーにも相手にも送らない
- [ ] **V-6 `onlyAllowTrustedDevices` を検討**。V-4 が無い状態では倒せない

### V-4 を今やらない理由

FFI に `bootstrapCrossSigning()` はあり、3つの要求（端末鍵・署名鍵・署名）を返す。
それを運ぶ口を Firestore 側に作れば、技術的には通る。

**問題は鍵の置き場所。** クロスサイニングの秘密鍵が端末と一緒に消えると、
再インストールのたびに**別人の身元**が生まれる。相手には毎回「身元が変わりました」と
出る。**いまより悪い。** 警告が日常になると、本物の警告も読まれなくなる。

つまり V-4 の前提は「秘密鍵が端末の消去を生き延びること」で、Matrix ではそれを
SSSS（秘密の保管庫）が担う。Rinowa には既に**ドライブ + 暗証番号のバックアップ**が
あるので、そこへ `CrossSigningKeyExport` を入れるのが自然な置き場所になる。

ただしそれは**バックアップの形式を変える**ということで、0.19.3 で実機検証を通した
ばかりのところに手を入れる。数日かかる作業で、片手間にやると壊すほうが大きい。

**順番として、先に V-3 を実機で確かめる。** 鍵が変わったときの知らせが本当に出るかを
見ないまま、その上に身元の仕組みを積まない。

### 守ること

**照合していない状態を「安全」と表示しない。** 検証済み・未検証・鍵が変わった、の3つを
区別して見せる。区別しないなら、検証機能は飾りになる。

---

## Rinowa Direct（時期未定・Cloud とは独立）

> **Cloud when far. Direct when near.**

近くにいる Rinowa ユーザー同士を、可能ならサーバーを経由せず直接つなぐ。
設計は [DIRECT_ARCHITECTURE.md](DIRECT_ARCHITECTURE.md) と
[DIRECT_THREAT_MODEL.md](DIRECT_THREAT_MODEL.md)。

**Cloud Messaging を置き換えない。** Direct を OFF にしても Rinowa は完全に動く。

### Direct-0 — 設計（**完了**）

- [x] Product Vision へ統合
- [x] DIRECT_ARCHITECTURE.md
- [x] DIRECT_THREAT_MODEL.md
- [x] Transport / Route / AssetResolver の抽象案
- [x] Android / iOS の候補技術を公式資料で比較
- [x] Android ↔ iPhone の実現可能性評価
- [x] messageId のクライアント採番（**唯一の先行コード変更**）

**実装はしない。** 抽象層だけ先に作らない — 中身の無い層は複雑性でしかない。

### Direct-1 — 最小検証

Android 2台で、**Cloud を一切使わず** `Hello` を送る。
UI は Developer Test Screen でよい。

測るもの: discovery latency / connection latency / message latency /
connection success rate / battery。**すべて Cloud 経由と比較する。**

ここで数字が出なければ、Direct-2 へ進まない。

### Direct-2 — 実用化

- 自動 peer 認識（利用者は何もしない）
- Device Identity の暗号学的検証
- Asset 転送（**ここが本命**）
- Cloud fallback と二重配信の防止

### Direct-3 — iOS と Cross-platform

- iOS 実装
- Android ↔ iPhone

> **調査で判明した制約。** Nearby Connections の iOS SDK は **Wi-Fi LAN medium のみ**対応。
> つまり **Android ↔ iPhone は「同じ Wi-Fi にいる」ときだけ**成立し、**完全オフラインでは成立しない**。
> iOS はバックグラウンドで service UUID を非公開の overflow area に入れるため、
> Android から BLE で発見することもできない。
>
> 家・職場・学校は同一 Wi-Fi であることが多いので価値はある。
> しかし**災害・圏外の場面で Android ↔ iPhone は動かない。** 隠さず制約として扱う。
> Google が iOS 向け BLE medium を作業中と表明しているので、出たら再評価する。

### Research（実装しない）

- **Mesh Relay** — `A ↔ B ↔ C`。routing / privacy / battery / abuse /
  malicious relay / **中継への同意** が同時に複雑になる。研究項目に留める
  → **調査完了 2026-08-17: `docs/RESEARCH_MESH.md`**。
  結論は「**E2EE が前提条件**であり、それが無い今は着手しない」。
  上の overflow area の話がそのまま効いて、**iPhone は中継者になれない**
- Nearby Friend Request — 知らない人との近距離での friend 交換。**別設計**
- Direct で送ったメッセージの Cloud History 同期方針（案 A / B / C の比較）。
  E2EE の設計が決まってから選ぶ

---

## Yosegi v1（2026-08-17 凍結・実装済み・未投入）

**仕様凍結: [`YOSEGI_V1_SPEC.md`](YOSEGI_V1_SPEC.md)。実装: `android/core/wire/`。**

- [x] W-3 現実的コーパスでの再検証 — **JSON の 23.8%**、全長さ帯で膨張ゼロ
- [x] Fuzz — 92,362 ケース、契約違反ゼロ。**実バグ1件を検出して修正**
- [x] Security review — [`RESEARCH_COMPRESSION_SECURITY.md`](RESEARCH_COMPRESSION_SECURITY.md)
- [x] Yosegi v1 Freeze
- [x] Kotlin 実装 + 33テスト（往復・前方互換・不正入力）
- [x] 切替可能な統合（`MessageWire.kt` / `YosegiRollout`、**全フラグ off**）
- [x] **W-1 実機性能 — 完了（3機種）**。Pixel 10 で encode 1.18 / decode 1.70 µs/通、
      **下限の Galaxy A23 5G でも encode 2.80 / decode 4.28 µs/通**。
      **削減率は3機種すべて 78% で完全一致** — 「圧縮率は機種に依存しない」という
      研究中の前提が事後に確認された
- [ ] W-2 バッテリ — **未測定。数字は書かない**
- [ ] 事前配布辞書 8 KB の生成と同梱
- [ ] Direct-2 の実メッセージ経路へ接続（Direct-2 待ち）

### 実装中に判明した重要な訂正

**Yosegi を Firestore のメッセージ文書に使ってはいけません。**
`firestore.rules` の保証はフィールド単位で動いており、
不透明な blob にすると「管理者でも本文を読めない」という**構造的保証が、
クライアントの行儀への期待に格下げされます**。
1通200バイトのためにその取引はしません。詳細は `RESEARCH_WIRE_FORMAT.md` §13。

Yosegi が使われるのは **Direct / Mesh / 一括エクスポート**だけです。

---

## 通信基盤の研究（2026-08-17 完了・実装は Prototype 1 以降）

**測定コードは `research/wire/`。結論は3本のドキュメントに。**

| ドキュメント | 扱う範囲 |
|---|---|
| `docs/RESEARCH_WIRE_FORMAT.md` | データ形式・圧縮方式の実測と採否 |
| `docs/RESEARCH_ADAPTIVE_TRANSPORT.md` | 経路ごとの詰め方、asset 解決順序 |
| `docs/RESEARCH_MESH.md` | 災害時 store-and-forward の制約調査 |

### 採用が決まったもの（実装は未着手）

**推奨構成は2段だけ。JSON 240 B → 43 B（17.9%）。**

- [ ] **A-1 Yosegi v1（Rinowa 専用バイナリ形式）** — 240 B → 72 B。
      **圧縮アルゴリズムを一切使わずに 70% 削減。2.1 µs/通。最優先**
- [ ] **A-4 Asset の事前問い合わせ** — 相手が持っていれば ID だけ送る。sticker で3桁の差
- [ ] A-2 版・能力のネゴシエーション（Direct ハンドシェイクに追加）
- [ ] A-5 deflate + 事前配布辞書 32 KB — 72 B → 43 B。
      **`java.util.zip.Deflater.setDictionary`。追加依存ゼロ**

### 採らなかったもの

**独自圧縮方式は、作って測って負けたので捨てました。**
ETX（order-1 算術符号）は自作の KANA8 に負け、
ETX2（order-2）は未知の文で悪化したうえモデルが 273 KB。
**既存の deflate + 辞書に全域で負けています。** 詳細は `RESEARCH_WIRE_FORMAT.md` §5.4。

- **KANA8 は保留。** 辞書があると上積みは 1.8% だけ（辞書が無ければ 22%）。
  復活条件は `RESEARCH_WIRE_FORMAT.md` §6.3 に明記
- **zstd セッション圧縮は不採用。** Yosegi の後では 72 → 64 B しか稼がず、
  1フレーム落ちると会話全体が壊れる。**得るものが小さく失うものが大きい**
- **経路ごとにコーデックを変える設計も不採用。** 分岐は「1通消える」経路を増やすだけだった

短文では zstd も brotli も **payload を大きくします**（Yosegi + zstd L3 は 89% が膨張）。
「とりあえず圧縮」はしない。

### 未了

**W-1 はここで未了と書いていたが、Yosegi v1 の節では完了になっていた。**
実際は完了（3機種で実測、Pixel 10 で encode 1.18 µs/通）。矛盾を残さないよう消す。

- [ ] W-2 消費電力の実測（**デスクトップでは測れない**）
- [ ] W-4 辞書の更新をどう配るか（版ずれ時の挙動）
- [ ] W-3 **本物の日本語チャットコーパスでの再測定** — 合成コーパスの偏りはここでしか解けない。
      Yosegi 節の「W-3 現実的コーパスでの再検証」は**合成コーパスの改良版**で、これとは別
- [ ] M-1〜M-5 Mesh の実測（M-3 は3台目、M-5 は iPhone が要る）

---

## 視覚フェーズ（時期未定・バックエンドとは独立）

Prototype 1 の前でも後でも着手できる。

### 現状（0.3.2 時点）— これはガラスではない

会話カード・コンポーザーのボタン・入力バーは、**影で浮かせ、縁に光を入れ、
押すと膨らんで発光する不透明な面**である。

> **ガラスではない。** ガラスは半透明で、背後が透けて、それがぼけている。
> 今のものにその要素は無い。0.3.2 で八角形を直すため透明度を捨てたので、さらに遠い。

それでも「前より良く、LINE とも違う」ため採用している。名前（`GlassSurface`）は
**目指す先を表しており、現状を表していない。**

### 段階1: 本物のすりガラス（未着手）

背後を取り込んでぼかす必要がある。Android では:

| 方式 | 条件 | コスト |
|---|---|---|
| `RenderEffect` で背景を再描画してぼかす | **API 31+** | 面ごとに背景をもう一度描く |
| ウィンドウレベルのぼかし | API 31+、システム設定に依存 | 画面全体、面ごとの制御が効かない |

**判断が必要な点:**

- minSdk 24 のままなら、API 31 未満では今の不透明な面へ退避する。
  **端末によって見た目が変わる**ことを許容するか
- **スクロールするリストへ入れてよいか。** 面ごとに背景を再描画するため、
  会話カードに入れると毎フレーム払う。**入れる前と後で必ず測る**
- 文字の可読性。読む時間が長いアプリで、背後が透ける面に文字を置いてよいか

**先に測る。** GPU は現在 99th で 2〜5ms、予算 8.33ms。余裕はあるが無限ではない。

### 段階2: NEON GLASS UI の組み込み（保留）

開発者本人が作った独自デザイン言語 1.4.0 を `design/neon-glass-ui/` へ保管済み。**未実装。**

屈折を捨てる方針を採ったため、**当初挙げた4つの論点のうち3つは消えた**
（API 33 の下限、View と Compose の接続、暗い背景の前提）。

残る論点は「ライト背景では屈折させる材料が無い」という一点で、
これは段階1（背後をぼかす）が入れば状況が変わる可能性がある。

詳細 → [design/neon-glass-ui/INTEGRATION_NOTES.md](../design/neon-glass-ui/INTEGRATION_NOTES.md)

### 守ること

**「スクロールが指に貼りついて感じられる」は Prototype 0 の合格条件である。**
見た目のためにそこを削らない。撤退条件を先に決め、悪化したら戻す。

## Prototype 2 — 運用に耐える形へ

利用実績が出てから着手する、と書いていたが、**暗号化フェーズの検証の副産物として
3項目が先に済んだ**。順番どおりに進まないこと自体は問題ではない。記録が現実と
食い違ったまま残ることが問題なので、ここに反映する。

- [ ] Block
- [ ] Report
- [ ] Rate limiting
- [ ] Spam protection
- [x] **Account recovery** — 消去 → 再ログイン → ドライブから復元を実機で2回通した（0.19.3）
- [ ] Data deletion
- [x] **Backup strategy** — 手動バックアップが2アカウントで通った。
      **自動化は未了**（暗証番号を端末に保存するかの判断が要る → RESEARCH_E2EE §3.2）
- [x] **Security Rules hardening** — 参加者の穴（0.19.0）に加え、**残りの節を同じ目で監査**
      して3件を塞いだ（0.20.2）: 一覧のプレビュー偽装・公開プロフィールの項目無制限・
      旧写真保管庫の書き込み口。規則テストは 64 → **89件**
- [ ] App Check hardening
- [ ] Abuse protection
- [ ] Monitoring
- **グループ限定 Sticker Pack**（Pack manifest による差分取得）
- **不適切なスタンプの報告と削除**（誰が判断し、どう実行するか）
- **重複排除の検討**（privacy / ownership / deletion / reference counting を解いてから）
- **運営側バックアップ**（scheduled backup、**復元手順を一度実際に試す**）
- **Cloud data retention の決定**（退会後どれだけ保持するか）

---

## Public Beta 前 — 必須項目

以下がすべて揃うまで公開しない。

- [x] **E2EE 方式検討**（独自暗号は設計しない。既存の検証済みプロトコルのみ）
      → Matrix の Olm / Megolm を採用。**RINOWA SIGIL** として実装・実機検証まで完了
      → [RINOWA_SIGIL.md](RINOWA_SIGIL.md)
- [ ] **鍵の検証**（V-1〜V-4）。**ここが LINE に負けている唯一の点。**
      → 「鍵の検証フェーズ」参照
- [ ] **セキュリティレビュー**（別途実施）。
      **外部レビューを1回受けた**（2026-08-25、P0 1件・P1 3件を指摘され、3件修正）。
      ただし E2EE 本体の暗号学的レビューは受けていない
- [ ] Privacy Policy
- [ ] Terms
- [ ] Data deletion
- [ ] Account deletion
- [ ] Abuse reporting
- [ ] Moderation policy
- [ ] Backend cost evaluation
- [ ] Scalability evaluation
- [ ] Apple / Google 配信要件確認

---

## iOS

**Prototype 0 は 2026-08-15 に完了した。** 上の禁止は解けているが、Mac が無い状況は
変わっていないので、方針そのものは維持する。

現状で書いてあるのは共有フォーマットだけ（`ios/RinowaCore/`、Swift 709行。
封筒・バックアップ書庫・バックアップ暗号）。Windows 用 Swift でビルドとテストは通している。
**Xcode は未使用。実機ビルド・シミュレータ・署名・提出は未経験。**

**Android Prototype 0 の操作感が確定するまで、iOS の実装コードは書かない。**

理由: Mac を常用できないため、ビルド未検証の Swift コードが大量に溜まるのが最大のリスク。
仕様が動く前に書くと、借りた Mac の数時間をコンパイルエラー修正だけで消費する。

先に用意するのは **UX 仕様の共通ドキュメント**（本 docs/ 以下）のみ。

### 運用

```
Windows 側で仕様・コード生成
        ↓
Git で同期
        ↓
Mac 利用日に Xcode ビルド / Simulator / 実機検証
        ↓
エラー回収
        ↓
Windows 側で修正
```

Mac を借りられる時間は月に数時間程度。この時間は **ビルドと実機検証だけに使う**。
設計や実装の試行錯誤を Mac 上でやらない。

### 触覚

`UIFeedbackGenerator` と `Core Haptics` を用途に応じて使い分ける。
**Android の値をそのまま移植しない。** → [HAPTIC_DESIGN.md](HAPTIC_DESIGN.md)

---

## App Store 公開について

開発者本人は13歳のため、本人名義で Apple Developer Program へ加入できない。

**この制約を理由に作業を止めない。** まずプロトタイプを完成させる。

本当に利用価値が確認できた段階で、保護者の正式な関与を含めた配信方法を検討する。
**保護者名義を単に借りる前提では進めない。**
