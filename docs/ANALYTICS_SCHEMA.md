# ANALYTICS SCHEMA

このスキーマは [PRIVACY_PRINCIPLES.md](PRIVACY_PRINCIPLES.md) に完全に従属する。
両者が矛盾した場合、PRIVACY_PRINCIPLES が優先される。

**目的は監視ではない。** UI 改善 / 操作改善 / 触覚改善 / パフォーマンス改善 /
実際に使われている機能の把握 / バグ検出 のためだけに存在する。

---

## 絶対規則

### 規則 1 — 文字列パラメータは存在しない

**イベントパラメータに `String` 型は使わない。**
`:core:analytics` の公開 API に `String` を受け取るオーバーロードを作らない。

許可される値の型:

| 型 | 用途 |
|---|---|
| `Int` | 件数、文字数、割合(0-100)、バケット番号 |
| `Long` | 経過時間(ms) |
| `Double` | 比率 |
| `Boolean` | フラグ |
| 事前定義 `enum` | 分類（`ConversationType` など） |

`enum` は Analytics 送信直前にライブラリ内部で `String` へ変換される。
**アプリ側のコードが文字列を作る箇所は存在しない。**

```kotlin
analytics.log(MessageSent(characterCount = text.length, ...))   // ✅
analytics.log(MessageSent(body = text))                          // ❌ コンパイルエラー
```

### 規則 2 — 送ってはいけないもの

| 禁止 | 理由 |
|---|---|
| メッセージ本文 / 下書き | 第1原則 |
| 会話 ID / メッセージ ID / ユーザー ID | 社会グラフが復元できてしまう |
| 表示名 / メール / 電話番号 / 連絡先 | 個人特定 |
| 選択された絵文字そのもの | パレット内 index のみ送る |
| 添付ファイル名 | 内容が推測できる |
| 精密な位置情報 | 不要 |
| 広告 ID | `google_analytics_adid_collection_enabled = false` |

### 規則 3 — 数えるのはクライアント側

文字数はクライアントで計算し、**数値だけ**を送る。

```
character_count: 24         ✅
message_body: "こんにちは"    ❌
```

---

## User Properties（イベントではなく属性）

個人ではなく「どの環境か」を表すもののみ。

| 名前 | 型 | 目的 | Privacy risk |
|---|---|---|---|
| `app_version_code` | Int | バージョン別の比較 | NONE |
| `os_api_level` | Int | OS 別の挙動差 | NONE |
| `device_category` | enum(`PHONE_SMALL`/`PHONE_LARGE`/`FOLDABLE`/`TABLET`) | レイアウト検証 | NONE |
| `haptic_tier` | enum(`T4`…`T_MINUS_1`) | 端末の触覚能力分布 | NONE |
| `haptics_intensity` | enum(`OFF`/`SUBTLE`/`NORMAL`/`STRONG`) | 触覚設定の分布 | NONE |
| `theme_mode` | enum(`LIGHT`/`DARK`/`SYSTEM`) | どちらを作り込むべきか | NONE |

**端末モデル名は送らない**（少数派端末では個人特定に繋がるため、カテゴリのみ）。

---

## イベント定義

`risk` 列: **NONE** = 個人情報に繋がらない / **LOW** = 集計すれば無害 / **MEDIUM** = 設計上の注意が必要

### 1. 基本利用統計

| イベント | パラメータ | 型 | 目的 | risk |
|---|---|---|---|---|
| `app_opened` | `cold_start` | Boolean | 起動性能の把握 | NONE |
| | `startup_ms` | Long | 起動時間の改善 | NONE |
| `app_backgrounded` | `foreground_ms` | Long | セッション長 | NONE |
| `crash` | — | — | Crashlytics が自動収集 | **MEDIUM** ※ |

※ **Crashlytics の注意**: カスタムキー・カスタムログに本文が混入しないこと。
例外メッセージに本文が含まれる可能性のある箇所（パーサ等）では、
例外を再ラップして本文を除去してから記録する。

DAU / WAU / MAU は個別イベントではなく、上記から集計側で導出する。

### 2. Active Time

**単純な foreground 時間を「利用時間」にしない。** 開きっぱなしの時間が混入するため。

```
foreground
   ↓
ユーザー操作あり  →  ACTIVE
   ↓
一定時間(60秒)無操作  →  INACTIVE
   ↓
再操作  →  ACTIVE
```

| イベント | パラメータ | 型 | 目的 | risk |
|---|---|---|---|---|
| `active_period_ended` | `active_ms` | Long | 実際に操作していた時間 | NONE |
| | `foreground_ms` | Long | 開いていた時間（対比用） | NONE |
| | `interaction_count` | Int | 操作密度 | NONE |
| | `screen` | enum(`CHAT_LIST`/`CHAT`/`SETTINGS`/`HAPTIC_LAB`) | どの画面に時間を使うか | NONE |

**過剰な監視にしないための制約:**
- 個々の操作のタイムスタンプ列は送らない。**集計値のみ。**
- 何を入力したか・何を読んだかは一切含まない。
- ACTIVE / INACTIVE の遷移そのものはイベント化しない（粒度が細かすぎる）。

### 3. メッセージング

**本文は収集しない。**

| イベント | パラメータ | 型 | 目的 | risk |
|---|---|---|---|---|
| `message_sent` | `character_count` | Int | 平均メッセージ長 → 入力 UI 設計 | LOW |
| | `conversation_type` | enum(`DIRECT`/`GROUP`) | DM / グループ比率 | NONE |
| | `is_reply` | Boolean | 返信機能の利用率 | NONE |
| | `attachment_type` | enum(`NONE`/`IMAGE`/`VIDEO`/`FILE`) | 添付の利用率 | NONE |
| | `delivery_latency_ms` | Long | 送信性能 | NONE |
| | `send_success` | Boolean | 成功率 | NONE |
| `message_send_failed` | `failure_reason` | enum(`NETWORK`/`AUTH`/`RATE_LIMIT`/`SERVER`/`UNKNOWN`) | 失敗原因の分布 | NONE |
| | `retry_count` | Int | リトライ設計の妥当性 | NONE |
| `message_received` | `conversation_type` | enum | 受信量の把握 | NONE |
| | `attachment_type` | enum | 添付の受信率 | NONE |
| `reaction_added` | `palette_index` | Int | **どの絵文字が使われるか（index のみ）** | LOW |
| | `conversation_type` | enum | | NONE |
| `reply_sent` | `conversation_type` | enum | 返信の利用率 | NONE |

> `character_count` が LOW リスクである理由:
> 単体では無害だが、極端に長い値は他情報と組み合わせて特徴になりうる。
> **500 以上はバケット化**（500, 1000, 2000, 5000+）して送る。

> `palette_index` は **固定パレット内の位置**。絵文字の文字そのものは送らない。
> パレットが変わったらバージョンとセットで解釈する必要があるため、
> `reaction_palette_version` を User Property に持つ。

### 4. UX

これにより「返信スワイプの開始者の何％が途中でキャンセルしたか」を測定できる。

| イベント | パラメータ | 型 | 目的 | risk |
|---|---|---|---|---|
| `reply_swipe_started` | — | — | 分母 | NONE |
| `reply_swipe_threshold_reached` | `time_to_threshold_ms` | Long | 閾値距離が適切か | NONE |
| `reply_swipe_cancelled` | `max_drag_ratio` | Int (0-100) | どこまで引いて諦めたか | NONE |
| | `passed_threshold` | Boolean | 超えてから戻したか | NONE |
| `reply_swipe_completed` | `duration_ms` | Long | 操作の所要時間 | NONE |
| `message_long_pressed` | `hold_ms` | Long | 長押し時間の調整 | NONE |
| `long_press_cancelled` | `hold_ms` | Long | 長押しが長すぎないか | NONE |
| `reaction_picker_opened` | — | — | 分母 | NONE |
| `reaction_picker_dismissed` | `open_ms` | Long | 迷っている時間 | NONE |
| | `hovered_count` | Int | 何個の候補を見たか | NONE |
| `reaction_selected` | `palette_index` | Int | 選択分布 | LOW |
| | `open_ms` | Long | 決定までの時間 | NONE |
| `composer_opened` | — | — | 分母 | NONE |
| `composer_abandoned` | `character_count` | Int | **書いたのに送らなかった量** | LOW |
| | `compose_ms` | Long | | NONE |
| `attachment_picker_opened` | — | — | 添付導線の利用率 | NONE |
| `chat_opened` | `unread_bucket` | Int (0/1/2-5/6-20/21+) | 未読量と行動 | NONE |
| `scroll_burst` | `distance_dp` | Int | スクロール量 | NONE |
| | `fling_count` | Int | フリック回数 | NONE |

### 5. Haptics

**個別発火ごとにイベントを送らない。** 量が膨大になり、かつ個人の操作列を復元しうるため。
**セッション単位の集計値を1回だけ送る。**

| イベント | パラメータ | 型 | 目的 | risk |
|---|---|---|---|---|
| `haptic_session_summary` | `haptics_enabled` | Boolean | ON 率 | NONE |
| | `tier_used` | enum(`T4`…`T_MINUS_1`) | 端末の触覚能力分布 | NONE |
| | `send_count` | Int | | NONE |
| | `selection_count` | Int | | NONE |
| | `threshold_count` | Int | 返信スワイプ成立回数 | NONE |
| | `reaction_count` | Int | | NONE |
| | `success_count` | Int | | NONE |
| | `error_count` | Int | **エラー触覚が多い = どこかが失敗している** | NONE |
| | `destructive_count` | Int | | NONE |
| | `suppressed_count` | Int | 抑制ルールが効きすぎていないか | NONE |
| `haptics_setting_changed` | `enabled` | Boolean | | NONE |
| | `intensity` | enum(`OFF`/`SUBTLE`/`NORMAL`/`STRONG`) | どの強度が好まれるか | NONE |

### 6. Feedback

| イベント | パラメータ | 型 | 目的 | risk |
|---|---|---|---|---|
| `feedback_submitted` | `category` | enum(`BUG`/`FEATURE`/`UI`/`HAPTIC`/`OTHER`) | 種別分布 | NONE |
| | `body_length` | Int | 記入量（本文は送らない） | NONE |
| | `has_screenshot` | Boolean | | NONE |
| `feedback_voted` | `direction` | enum(`UP`/`DOWN`/`UNVOTE`) | | NONE |
| `roadmap_viewed` | — | — | 公開ロードマップの関心度 | NONE |
| `changelog_viewed` | `version_code` | Int | | NONE |

> **フィードバック本文は Analytics へ送らない。**
> 本文はユーザーが開発者へ届ける意図で明示的に書いたものなので Feedback バックエンドに保存されるが、
> それは Analytics とは別系統であり、Analytics へは長さだけを送る。
> この区別を曖昧にしない。

### 7. Privacy UI

| イベント | パラメータ | 型 | 目的 | risk |
|---|---|---|---|---|
| `privacy_screen_opened` | — | — | 関心度 | NONE |
| `analytics_opt_out_changed` | `opted_out` | Boolean | オプトアウト率 | NONE |

`opted_out = true` になった時点以降、このイベント以外は一切送信しない。

---

## Prototype 0 における扱い

**Prototype 0 では Analytics を実送信しない。**

- API（型定義）と `NoOpAnalytics` 実装のみを用意する
- `DebugAnalytics` は logcat へ「イベント名 + 数値パラメータ」のみ出力する
  （本文が存在しないことを型が保証しているので、ログにも本文は出得ない）
- Firebase 接続は Prototype 1 から

---

## レビュー時のチェックリスト

Analytics に触れる変更では毎回確認する。

- [ ] 新しいパラメータに `String` 型がないか
- [ ] `enum` の値そのものに自由入力が混ざっていないか
- [ ] ID（会話・メッセージ・ユーザー）を送っていないか
- [ ] Crashlytics のログ・カスタムキーに本文が入る経路がないか
- [ ] 個別発火の高頻度イベントを追加していないか（集計に置き換えられないか）
- [ ] [PRIVACY_PRINCIPLES.md](PRIVACY_PRINCIPLES.md) と矛盾しないか
