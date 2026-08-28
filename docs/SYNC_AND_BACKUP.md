# SYNC AND BACKUP

> **Device can be replaced. Account persists.**

端末依存アプリにしない。故障・機種変更・アプリ再インストールのあとでも、
アカウントへ再ログインすれば主要データが戻る構造にする。

このドキュメントは [PRIVACY_PRINCIPLES.md](PRIVACY_PRINCIPLES.md) に従属する。

---

## 1. SYNC / BACKUP / CACHE を混同しない

UI でも内部設計でも、この3つは別物として扱う。混ぜると必ず事故が起きる。

| | 目的 | 失われたら | ユーザーから見た意味 |
|---|---|---|---|
| **SYNC** | 複数端末とクラウドで**現在の状態**を揃える | 端末間でズレる | 「どの端末でも同じ」 |
| **BACKUP** | 誤削除・障害から**過去の状態**を復元する | 戻せなくなる | 「消しても戻せる」 |
| **CACHE** | 再取得可能なデータをローカルに置いて速くする | **何も失われない** | （見えなくてよい） |

さらに区別が必要なもの:

- **ユーザー向け Account Sync**（本人のデータを本人の新端末へ）
- **運営側 Disaster Recovery Backup**（サービス障害からの復旧）

この2つは目的も権限も別である。**後者を理由に前者の範囲を広げない。**

---

## 2. データの性質ごとに同期方法を分ける

「全部を丸ごとバックアップ」はしない。性質が違うものを同じ扱いにすると、
不要なものまでクラウドへ送ることになる。

| データ | 方式 | 備考 |
|---|---|---|
| Account | Firebase Authentication 等 | |
| Profile | **Cloud sync** | 表示名、アイコン |
| User settings | **Cloud sync** | appearance / notification / **haptic** / privacy / accessibility |
| Friends / Relationships | **Cloud sync** | |
| Groups | **Cloud sync** | |
| Sticker ownership | **Cloud sync** | `ownedStickerIds` / `favoriteStickerIds` / `recentStickerIds`。**画像本体ではなく参照情報** |
| Custom Sticker Master Asset | **Cloud Storage** | 正本。→ [STICKER_ARCHITECTURE.md](STICKER_ARCHITECTURE.md) |
| Local Sticker Cache | **同期しない** | 新端末では必要になったものから再取得 |
| Feedback / Votes | Cloud sync（アカウントに紐づく範囲） | |
| Message history | §5 を参照 | 将来の E2EE を妨げない形にする |
| **Analytics / Diagnostics** | **完全に別系統** | §6 |

**触覚設定が同期対象である**ことは意図的である。
時間をかけて調整した強度設定が機種変更で消えるのは、この製品では体験の損失にあたる。

---

## 3. 新しい端末での復元

```
新端末
  ↓  ログイン
Authentication
  ↓
Profile 取得
  ↓
Settings 復元
  ↓
Friends / Groups 復元
  ↓
Sticker ownership / Pack manifest 取得
  ↓
必要な Asset だけ lazy download
  ↓
通常利用開始
```

**スタンプを100個持っているからといって、ログイン直後に100個落とさない。**
ログインが「待ち時間」になってはいけない。

取得の優先順位:

1. Favorites
2. 最近使ったもの
3. いま開いている会話に出てくるもの
4. 残りは実際に使うとき

---

## 4. Local Sticker Cache はバックアップしない

再取得できるものをバックアップに含めない。
Android の auto backup 対象からも除外する（`filesDir/stickers/` を含めない）。

理由は容量ではなく**正しさ**である。
キャッシュを復元すると「古い実体」が新端末に載り、
クラウド上の正本と食い違ったまま気付けない状態を作りうる。

---

## 5. Message History と将来の E2EE

初期 Prototype では E2EE を実装しない可能性が高い。
しかし **将来 E2EE を追加できなくなるような Message Model にしない。**

とくに次の前提を置かない。

> ❌ 「Cloud Backup が欲しいから、将来もサーバーは本文を復号できる」

これを前提にすると、E2EE を入れる日にバックアップ機能を壊すか、
E2EE を諦めるかの二択になる。

将来必要になるのは:

```
Encrypted Message  +  Encrypted Backup  +  Secure Key Recovery
```

の3点セットである。

**現時点では独自の E2EE / Key Recovery を作らない。**
既存の十分に検証されたプロトコルを候補とし、別途セキュリティ設計を行う。
→ [PRIVACY_PRINCIPLES.md](PRIVACY_PRINCIPLES.md)

そのため、いま守るべき設計上の制約は次の1点に集約される。

> **メッセージ本文は、他のあらゆるメタデータから分離した1つのフィールドに閉じ込める。**
> 検索・集計・表示のためにサーバー側が本文を読む前提の機能を作らない。

---

## 6. ユーザーデータと Analytics を分離する

**Cloud Backup を導入することは、管理者が本文を読める理由にならない。**

これは [PRIVACY_PRINCIPLES.md](PRIVACY_PRINCIPLES.md) の第1原則の再確認である。
バックアップが増えても、禁止事項は1つも緩まない。

アーキテクチャ上も論理的に分離する。

```
┌─ User Content / Account Data ─┐      ┌─ Analytics / Diagnostics ─┐
│  メッセージ                    │      │  イベント（数値と enum）    │
│  スタンプ資産                  │  ✕   │  クラッシュ                │
│  プロフィール・設定             │ 交わ │  パフォーマンス            │
│  復元のためにある               │ らない│  改善のためにある          │
└───────────────────────────────┘      └───────────────────────────┘
```

Developer Console はサービス運営と UX 改善のためにある。
**ユーザーの Cloud Backup を覗くためのものではない。**
Console から User Content 側へ到達できる経路を作らない。

管理画面で使ってよいのは次に限る。

```
message count / character count / active duration / feature interaction
latency / success・failure / crash / version / haptics usage
```

文字数は**クライアントで計算して数値だけ**送る。本文は渡さない。

---

## 7. ユーザー向け「Account & Cloud」画面

> **「クラウドへ何を保存しているのか分からない」状態にしない。**

設定画面に `Account & Cloud`（または `Rinowa Cloud`）を設ける。

表示候補:

```
Account sync        有効 / 無効
Settings sync       有効 / 無効
Custom stickers     12 個
Groups              3 件
Last sync           2 分前
Storage used        1.4 MB
```

さらに将来:

- **Download my data** — 保存されているものを自分で取り出せる
- **Delete cloud data** — クラウド上のデータを消せる
- **Delete account** — アカウントごと消せる

これは [PRIVACY_PRINCIPLES.md](PRIVACY_PRINCIPLES.md) の
`Privacy → Analytics Data` 画面と対になる。
**片方は「何を測っているか」、もう片方は「何を預かっているか」を見せる。**

---

## 8. Firebase の使い方

Prototype 1 以降の候補:

```
Authentication / Cloud Firestore / Cloud Storage / Cloud Functions
Cloud Messaging / Crashlytics / Analytics / App Check
```

> **「Firebase にあるから全部使う」という判断は禁止。**

各サービスについて、
**それが無いと何ができないのか**を言えるまで採用しない。
採用しなかった理由も残す。

---

## 9. 運営側バックアップ（Disaster Recovery）

**Prototype 0 では不要。** Prototype 1 以降、実データが乗ってから設計する。

- database backup
- scheduled backup
- restore procedure（**復元手順を書いただけで終わらせず、一度実際に試す**）
- storage durability
- 誤削除時の対応手順

「バックアップを取っている」と「復元できる」は別である。
試していない復元手順は、無いのと同じと扱う。

---

## 10. 未決事項（勝手に決めない）

- **Account recovery のセキュリティ** — 端末を失った人をどう本人確認するか。
  ここを緩めると、なりすましでアカウントを奪える経路になる
- **Cloud data retention** — 退会後にデータをいつまで保持するか
- **Message history をクラウドに置くか** — 置く場合、E2EE 導入時の移行方法とセットで決める
- **Download my data の範囲と形式**
- **未成年利用に関する扱い** — 開発者本人が13歳であることを含め、
  公開段階では保護者の関与と併せて検討する（→ [ROADMAP.md](ROADMAP.md)）
