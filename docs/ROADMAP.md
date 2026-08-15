# ROADMAP

いきなり LINE 代替を完成させない。段階ごとに「検証したいこと」を1つに絞る。

---

## Prototype 0 — 操作感の検証（現在地）

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

- [ ] **触覚の補正**（1巡目を反映済み・判定待ち）
      「全体的に低く重い」を受けて周波数を引き上げた。実機での再判定が必要。
      未決の論点: `send` / `threshold` をプリミティブのままにするか、
      Envelope（周波数を制御できる代わりに最短 40ms）へ移すか。
      Haptic Lab の T4 / T3 固定比較で決める
- [ ] Canvas アイコンに `contentDescription` がない（TalkBack 未対応。P1 でまとめて）

### 決着した項目

- 送信側バブルが右端からはみ出す → **仕様として採用**（変更しない）
- リアクションは**1人1つ**。2つ目を選んだら1つ目を取り消して差し替える

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

## Prototype 1 — バックエンド接続

Prototype 0 の操作感が確認できた **後** に着手する。**少人数の友人限定。**

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

- Firestore Security Rules で、会話参加者以外が本文を読めないこと
- **管理者アカウントに本文への特権的読み取り経路が存在しないこと**
- Crashlytics のカスタムキー・ログに本文が入らないこと
- Analytics のイベントに `String` パラメータが1つもないこと

---

## NEON GLASS UI の組み込み（時期未定・独立）

開発者本人が作った独自デザイン言語 NEON GLASS UI 1.4.0 を
`design/neon-glass-ui/` へ保管済み。**未実装。**

バックエンド作業とは独立しているため、Prototype 1 の前でも後でも着手できる。
ただし着手前に4つの論点を決める必要がある。

1. **ライト先行という決定と、ネオンガラスは噛み合うか**（最大の論点）
2. View ベースと Compose の接続（**メッセージバブルには使えない**）
3. minSdk 24 と要求 29 の不一致
4. GPU 予算（NEON INSTINCT で中央値 9ms。120Hz の予算は 8.3ms）

詳細と判断材料 → [design/neon-glass-ui/INTEGRATION_NOTES.md](../design/neon-glass-ui/INTEGRATION_NOTES.md)

**4 は特に重要。** 「スクロールが指に貼りついて感じられる」は Prototype 0 の合格条件であり、
見た目のためにそこを削るのは製品の前提と矛盾する。**入れる前と後で測ってから決める。**

## Prototype 2 — 運用に耐える形へ

利用実績が出てから着手する。

- Block
- Report
- Rate limiting
- Spam protection
- Account recovery
- Data deletion
- Backup strategy
- Security Rules hardening
- App Check hardening
- Abuse protection
- Monitoring
- **グループ限定 Sticker Pack**（Pack manifest による差分取得）
- **不適切なスタンプの報告と削除**（誰が判断し、どう実行するか）
- **重複排除の検討**（privacy / ownership / deletion / reference counting を解いてから）
- **運営側バックアップ**（scheduled backup、**復元手順を一度実際に試す**）
- **Cloud data retention の決定**（退会後どれだけ保持するか）

---

## Public Beta 前 — 必須項目

以下がすべて揃うまで公開しない。

- [ ] **E2EE 方式検討**（独自暗号は設計しない。既存の検証済みプロトコルのみ）
- [ ] **セキュリティレビュー**（別途実施）
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
