# STICKER ARCHITECTURE

スタンプは装飾ではなく、**主要なコミュニケーション手段**として扱う。

LINE 等で実証されているとおり、スタンプは文章の代替ではなく独立した表現である。
RELAY/Echo はこれを廃止せず、リアクションで代替しようともしない。
ただし独自の方向として **「巨大なショップを用意する」のではなく「自分たちで簡単に作れる」** ことを軸に置く。

このドキュメントは [PRIVACY_PRINCIPLES.md](PRIVACY_PRINCIPLES.md) に従属する。
矛盾した場合は PRIVACY_PRINCIPLES が優先される。

---

## 4つの原則

```
PROCESS ON DEVICE          端末で加工する。元写真をサーバーへ上げない
STORE ONCE                 完成画像はクラウドに1つ。送信のたびに上げない
REFERENCE BY ID            メッセージが運ぶのは stickerId だけ
CACHE LOCALLY,             一度取得したら端末に持つ。表示のたびに落とさない
  DOWNLOAD ONLY WHEN NEEDED
```

**これはコスト削減のためだけの設計ではない。**
目的は、即座に描画されること、オフラインでも破綻しないこと、通信を待たされないこと、
そして将来のスケールに耐えること。
**通信費のために体験を犠牲にしない。**

---

## 1. メッセージはスタンプ画像を運ばない

```
❌ message { type: STICKER, imageBase64: "iVBORw0KG..." }
✅ message { type: STICKER, stickerId: "st_7f3a..." }
```

画像 binary / base64 を Message Document へ埋め込む設計は **禁止**。

理由は3つある。

1. **同じスタンプを1,000回送れば、1,000回分の容量を払う**
2. **Firestore のドキュメントサイズ上限を圧迫し、会話の読み込みが重くなる**
3. **将来 E2EE を入れるとき、本文と添付の扱いが分離できていないと設計が詰む**

受信側は `stickerId` を見て自分のローカルから解決する。
**送信者は「相手がこのスタンプを持っているか」を確認しない。**
常に ID だけを送り、キャッシュ管理は受信クライアントの責任とする。

---

## 2. 受信時の解決フロー

```
stickerId 受信
   │
   ├─ Local Sticker Store を確認
   │     │
   │     ├─ HIT  → 即座に描画（ネットワーク接続なし）
   │     │
   │     └─ MISS → Cloud から1回だけ取得
   │                  ↓
   │              content hash で整合性を確認
   │                  ↓
   │              Local へ保存
   │                  ↓
   │              描画
```

MISS 中はプレースホルダを出す。**メッセージ一覧そのものを待たせない。**

---

## 3. ストレージの3分類

**OS が自由に削除してよい一時 cache だけを正本にしてはいけない。**
同時に、**端末だけを正本にしてもいけない。** 機種変更・再インストール・端末故障があるため。

| 分類 | 置き場所 | 消えたら | 例 |
|---|---|---|---|
| **A. Persistent Local Asset** | `filesDir/stickers/` | クラウドから再取得できる | 取得済みスタンプ画像 |
| **B. Cache** | `cacheDir/` | 何も失われない | サムネイル、デコード結果 |
| **C. Master（正本）** | Cloud Storage | **失われる** | カスタムスタンプの完成画像 |

Android の `cacheDir` は OS が容量逼迫時に予告なく削除する。
スタンプ画像を `cacheDir` にだけ置くと、**通信できない場所で表示できなくなる。**
そのため取得済みアセットは A（`filesDir`）へ置き、B とは別に扱う。

そして **A も正本ではない。** 正本は常に C（クラウド）に1つある。
→ [SYNC_AND_BACKUP.md](SYNC_AND_BACKUP.md)

---

## 4. データモデル

```
StickerAsset
  stickerId        安定した一意 ID。メッセージが参照するのはこれだけ
  packId           所属パック
  contentHash      SHA-256（16進文字列）
  width / height   px
  byteSize
  format           WEBP / PNG
  origin           BUILT_IN / CUSTOM / GROUP
  createdAtMs

StickerPack
  packId
  ownerId          組み込みパックは null
  title
  visibility       PRIVATE / GROUP / SHARED
  version          差分取得のための単調増加
  stickerIds
  createdAtMs / updatedAtMs

StickerOwnership（アカウントに紐づく参照情報。画像本体ではない）
  ownedStickerIds
  favoriteStickerIds
  recentStickerIds
```

`visibility` に **`PUBLIC` は用意しない。** Prototype では不要であり、
公開共有は moderation・権利・削除要求の設計が済むまで存在させない。

### Pack manifest による差分取得

グループへ新規参加したとき、パック全体を落とし直さない。

```
Pack Manifest（packId, version, stickerIds, 各 contentHash）を取得
      ↓
ローカルに無い／hash が違うものだけを列挙
      ↓
不足分のみ取得
```

---

## 5. Content Hash

各 Sticker Asset は SHA-256 の content hash を持つ。

用途:

- **完全性の確認** — 取得したバイト列が期待どおりか
- 同一ファイルの判定
- キャッシュの検証
- 将来の重複排除の土台

### hash は暗号ではない

> **HASH ≠ ENCRYPTION**
>
> content hash を秘密情報として扱わない。
> 「hash を知っている＝アクセス権がある」という設計にしない。
> アクセス制御は認証と Security Rules で行う。

[PRIVACY_PRINCIPLES.md](PRIVACY_PRINCIPLES.md) の
**独自暗号方式を設計しない**という方針は維持される。ハッシュはその例外ではなく、
そもそも暗号ではない。

---

## 6. 重複排除は「まだやらない」

同一画像が複数回登録されたとき、Storage 上の同一 Asset を共有参照できる可能性はある。
しかし **「SHA-256 が同じだから全ユーザーで共有」としてはいけない。**

先に解く必要がある問題:

| 論点 | 何が起きるか |
|---|---|
| **privacy** | 同じ hash を持つことで「同じ画像を持っている」ことが第三者に判る |
| **ownership** | 誰の所有物か。先に登録した人か、全員か |
| **deletion** | 1人が削除要求したとき、参照している他者はどうなるか |
| **reference counting** | 参照が0になるまで実体を消せない |
| **abusive content** | 1件の不適切画像が全参照者に波及する |
| **authorization** | hash を知っているだけの第三者が取得できてはならない |

**Prototype では実装しない。** データモデルが将来対応できる形（contentHash を持つ）
になっていれば十分とする。

---

## 7. 端末内で処理する（ON DEVICE）

カスタムスタンプ作成の画像処理は、可能な限り端末内で完結させる。

```
元写真（端末内）
   ↓  crop / rotate / resize
   ↓  背景除去
   ↓  文字合成 / 位置・拡大縮小・回転
   ↓  圧縮 / フォーマット変換
   ↓  サムネイル生成 / ハッシュ計算
完成した小容量 Sticker Asset
   ↓
完成物だけを Cloud へ
```

**元写真は Echo のサーバーへアップロードしない。**

これはコストの話ではなくプライバシーの話である。
元写真には、スタンプにした部分以外の情報（背景、写り込んだ他人、位置情報を含む Exif）が
含まれている。サーバーへ送れば、それらを送ったことになる。

### 背景除去

**OS のオンデバイス機能を優先する。**
Android では ML Kit の Subject Segmentation 等、端末内で完結する手段を第一候補とする。

**クラウド AI を必須にしない。** 利用できない端末では、
背景除去を省略してもスタンプが作れる導線にする。

### Exif

書き出し時に **位置情報を含むメタデータを除去する。** 完成画像に元写真の Exif を残さない。

---

## 8. Asset の制約

極端な Storage abuse を防ぐため、Prototype 段階から上限を設ける。
正確な値は画質検証後に調整してよい。

| 項目 | 候補値 |
|---|---|
| 最大寸法 | 512 × 512 |
| フォーマット | WebP（透過あり）を第一候補 |
| 最大バイト数 | 厳格な上限を設ける（要検証、目安 200KB） |
| 元写真の保存 | **しない** |
| β期間中のアカウント当たり枚数 | 上限を設ける |

Cloud Storage Security Rules でも重ねて検証する。

- 認証されているか
- そのパスの所有者か
- サイズ上限内か
- MIME / content type が想定どおりか

**クライアント側のチェックだけを信用しない。**

---

## 9. Haptics と Expression

スタンプを単なる画像機能に閉じない。将来的に

```
Sticker + Animation + Haptic  =  Echo 独自の Expression
```

へ拡張できる設計とする。→ [HAPTIC_DESIGN.md](HAPTIC_DESIGN.md)

### 受信側の安全設計（重要）

> **送信者が任意の振動パターンを受信者へ送りつけられる構造にしない。**

送信側が選べるのは **semantic type だけ**。

```
CELEBRATION / LAUGH / LOVE / SURPRISE
```

受信側クライアントが、自分の設定・OS・端末能力に応じて
**ローカルで安全なパターンへ変換**する。

ユーザー生成コンテンツが振動時間や強度を直接指定できる構造は作らない。
作れば、それは嫌がらせの道具になる。

さらに:

- 受信のたびに強い触覚を自動再生しない
- `Settings → Haptics` で Expression の触覚を OFF / 強度調整できるようにする

---

## 10. Prototype での範囲

### Prototype 0（現在）— ネットワークなし

- [x] Sticker ID / Asset モデルの抽象化（**メッセージに画像を埋め込まない形を今から確立する**）
- [x] Local Sticker Store（`filesDir`、HIT/MISS を表現できる形）
- [x] 組み込みサンプルスタンプ
- [x] Sticker ボタン / Sticker picker
- [x] 送信と描画
- [x] 触覚の統合（picker 選択 = `selection`、送信 = `send`）
- [ ] Custom Sticker Composer — **UX の初期検討のみ。実装は P1**

Cloud Storage は接続しない。

### Prototype 1 — 接続

1. Authentication
2. Account データモデル
3. 実メッセージング
4. Sticker Master Asset（Cloud Storage）
5. Local Sticker Store の MISS 経路を実接続
6. ID ベースのスタンプメッセージ
7. 再インストール／ログイン後の復元
8. Custom Sticker Composer 実装
9. Analytics / Feedback

### Prototype 2 以降

- グループ限定パック
- 重複排除の検討
- 不適切コンテンツの報告と対応
- 保持期間ポリシー

---

## 11. Analytics で送ってよいもの

[ANALYTICS_SCHEMA.md](ANALYTICS_SCHEMA.md) に従う。特にスタンプ固有の注意:

> **`stickerId` を Analytics へ送ってはいけない。**
>
> カスタムスタンプの ID は、そのユーザーが作った固有の資産を指す。
> 送れば「誰が何を作り、誰に送ったか」が計測系から復元できてしまう。

送ってよいのは種別と数値のみ。

```
sticker_sent { sticker_kind: BUILT_IN|CUSTOM|GROUP, conversation_type, is_reply }   ✅
sticker_sent { sticker_id: "st_7f3a..." }                                          ❌
```

---

## 12. 未決事項（勝手に決めない）

以下は製品思想・プライバシー・セキュリティに関わるため、開発者本人の判断を要する。

- **カスタムスタンプの保持期間** — アカウント削除時、送信済みスタンプは相手の画面からどうなるか
- **不適切コンテンツの報告と削除** — 誰が判断し、どう実行するか
- **共有パックの取り消し** — 一度共有したパックを後から取り消せるか
- **重複排除を導入するか** — 上記6論点の解決後
- **背景除去にクラウド AI を使う選択肢を残すか** — 現方針は「使わない」
