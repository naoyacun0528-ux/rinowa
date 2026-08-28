# Rinowa Haptic Design — Nagori v1

触覚はこの製品の主要な差別化要素である。

---

## 基本原則

> **触覚は多ければ良いわけではない。**
>
> **視覚 / アニメーション / 音 / 操作 / 触覚 が一致することを優先する。**

- 各画面から直接 `Vibrator` API を呼ぶ構造にしない。**必ず共通 API を通す。**
- API は **「意味」** で定義する。`vibrate(20ms)` ではなく `haptics.send()` と書く。
- Android と iOS は **意味だけを共有する。値は共有しない。**
  振動子が根本的に異なる（Android: 端末ごとに LRA / ERM がばらつく、iOS: Taptic Engine）。
- 触覚だけで情報を伝えない。**必ず視覚の裏付けを持つ**（アクセシビリティ要件）。

### 実装してはならないこと

- ❌ 画面から直接 `Vibrator` / `performHapticFeedback` を呼ぶ
- ❌ 意味と対応しない「とりあえずの振動」を足す
- ❌ 視覚アニメーションと時間軸がずれた触覚
- ❌ ドラッグ中に連続発火させる
- ❌ Android で調整した数値を iOS へそのまま移植する

---

## 意味ベース・トークン

| トークン | 意味 | 使用箇所 |
|---|---|---|
| `selection` | 選択対象が変わった | リアクションピッカーのアイコン移動、リスト内フォーカス移動 |
| `navigation` | 画面が変わった | チャットを開く / 戻る |
| `softConfirm` | 軽い確定 | トグル ON/OFF、既読、下書き保存 |
| `send` | メッセージを送り出した | 送信ボタン |
| `threshold` | 引き返せない境界を超えた | **スワイプ返信の成立点**、プルダウン更新の発火点 |
| `reaction` | リアクションが確定した | 絵文字を選び切った瞬間 |
| `success` | 処理が成功した | 送信完了（サーバー確認）、設定保存完了 |
| `warning` | 注意が必要 | 文字数上限接近、接続不安定 |
| `error` | 失敗した | 送信失敗 |
| `destructive` | 破壊的操作が確定した | メッセージ削除、トーク削除 |

---

## 設計意図（重要なもの）

### `send` — メッセージ送信

**非常に短く、明確。** 立ち上がりを鋭く、減衰を早く。
「指から離れて飛んでいった」という感覚を作る。長い余韻は残さない。

視覚側のバブル飛び出しアニメーションの **開始フレームと同時に** 発火させる。
遅れると「反応が鈍い」と感じられる。

### `threshold` — スワイプ返信

**スワイプ中には乱発しない。**

```
指を動かす           → 触覚なし（視覚だけで進捗を伝える）
返信成立ラインを超えた → threshold を1回だけ
ラインより戻した      → deactivate を1回だけ（threshold より弱く）
指を離して確定        → 触覚なし（threshold で既に確定は伝わっている）
```

**見なくても指で成立がわかること** が、このトークンの合格条件。

### `reaction` — リアクションピッカー

アイコン間を移動するたびに `selection`（**極小**）。
最小発火間隔 40ms でスロットリングし、素早く動かしても連打にならないようにする。

選び切った瞬間だけ `reaction`（わずかに「咲く」立ち上がり）。

### `destructive` — 削除

**他のどのトークンとも明確に区別できること。**
低く・重く・余韻を持たせる。sharpness を意図的に下げて「鈍い」感触にする。
`send` や `success` と取り違えられたら設計失敗。

### `error` — 失敗

**単純に強く振動させない。**
「強く長く」ではなく、**詰まった 3 連 + 低い sharpness** で「引っかかった / 通らなかった」を表現する。

`success` が上昇 2 連であるのに対し、`error` は同じ強さが詰まって止まる。
この対比が意味を作る。

---

## Android 実装 — 段階的フォールバック

**上限は API 36 の Envelope**。そこから minSdk 24 (Android 7.0) まで下方フォールバックする。
（ビルドの compileSdk / targetSdk 上限は 37）

各 Tier の可否は **API レベルだけでなく実機の対応状況** で判定する。
API があっても振動子が対応していない端末があるため。

| Tier | API | 使用 API | 実機ゲート |
|---|---|---|---|
| **T4** | 36+ | `VibrationEffect.BasicEnvelopeBuilder` | `Vibrator.areEnvelopeEffectsSupported()` |
| **T3** | 31+ | `VibrationEffect.Composition`（`PRIMITIVE_THUD` / `PRIMITIVE_SPIN` を含む） | `arePrimitivesSupported()` |
| **T2** | 30+ | `VibrationEffect.Composition`（CLICK / TICK / LOW_TICK / QUICK_RISE / SLOW_RISE / QUICK_FALL） | `areAllPrimitivesSupported()` |
| **T1** | 29+ | `VibrationEffect.createPredefined(EFFECT_*)` | `areAllEffectsSupported()` |
| **T0** | 26+ | `createOneShot` / `createWaveform` | `hasAmplitudeControl()` |
| **T-1** | 24+ | `vibrate(long)` / `vibrate(long[], int)` | `hasVibrator()` |

### T4: BasicEnvelopeBuilder を使う理由

API 36 には 2 つの Envelope ビルダーがある。**`BasicEnvelopeBuilder` を採用する。**

| | `BasicEnvelopeBuilder` | `WaveformEnvelopeBuilder` |
|---|---|---|
| 制御軸 | intensity `0..1` / sharpness `0..1`（**正規化**） | amplitude / **絶対周波数 Hz** |
| 移植性 | 端末をまたいで同じ意味になる | 端末ごとに `getFrequencyProfile()` を読んで補正が必要 |
| 採用 | ✅ | ❌（端末依存が強すぎる） |

```
setInitialSharpness(s)
addControlPoint(intensity, sharpness, durationMs)   // 前の点から線形に遷移
```

実装上の制約:
- 最終コントロールポイントの intensity は 0 にする
- コントロールポイント数・各点の最小/最大 duration は
  `Vibrator.getEnvelopeEffectInfo()` の `getMaxSize()` /
  `getMinControlPointDurationMillis()` / `getMaxControlPointDurationMillis()` /
  `getMaxDurationMillis()` で実機ごとに制限される。**必ず実測値でクランプする。**

### 最上位 Tier が常に最良とは限らない（実測にもとづく設計判断）

Pixel 10 (Android 17 / API 37) を Haptic Lab で実測した値:

| 項目 | 実測値 |
|---|---|
| Envelope 対応 | 対応 |
| 最大コントロールポイント数 | 255 |
| **コントロールポイント 1 点の長さ** | **20 – 16383 ms** |

**1 点あたりの最小長が 20ms。**
つまり 2 点の Envelope は、どれだけ短く書いても **最短 40ms** になる。

`selection` は 8ms + 12ms = 20ms を意図していたが、実機では 40ms へ引き伸ばされる。
これは `PRIMITIVE_TICK`（およそ 10〜20ms）より明確に長く、**「極小」ではなくなる**。

したがって原則を次のように定める。

> **瞬間を意味するトークンはプリミティブ、時間的な形を持つトークンは Envelope。**

`HapticSpec.preferredMaxTier` により、端末の対応状況とは独立して
トークンごとに上限 Tier を指定する。

| Envelope を使わない（上限 T3） | Envelope を使う（上限 T4） |
|---|---|
| `selection` / `navigation` / `softConfirm` | `reaction`（立ち上がりに形がある） |
| `send`（鋭い立ち上がり・余韻なし） | `success` / `warning` / `error`（複数部分の時間構造） |
| `threshold` / `thresholdRelease` | `destructive`（低く重く、余韻を持つ） |

**この分類は実機で触って見直す。**
Haptic Lab の「Tier を固定して比較」で、同じ端末上で T4 と T3 を切り替えて判断できる。

### 必ず `VibrationAttributes.USAGE_TOUCH` を使う

```kotlin
vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
```

これにより OS の触覚設定・サイレントモード・省電力の扱いを正しく尊重する。
`USAGE_UNKNOWN` のままだと通知やアラーム相当に扱われる端末があり、
ユーザーが OFF にしたはずの触覚が鳴る。

---

## トークン定義値（初期値）

**この表の数値は「初期値」であり、実機で触って調整するためのもの。**
全ての値は `HapticTokenSpec` 1 ファイルに集約し、
実機フィードバック → 数値を 1 つ変える → 再ビルド、が即座にできる構造にする。

`I` = intensity(0..1), `S` = sharpness(0..1), `d` = duration(ms), `scale` = primitive scale(0..1)

### 周波数（＝高さ）を上げるレバーは Tier によって違う

| Tier | 周波数を制御できるか | 上げる方法 |
|---|---|---|
| T4 Envelope | **できる** | `sharpness` を上げる（0 = 低く鈍い、1 = 高く鋭い） |
| T3 / T2 Primitive | できない（OS 固定） | より高いプリミティブへ差し替える |
| T1 / T0 / T-1 | できない | 不可（振幅と長さのみ） |

プリミティブの高さの順序:

```
TICK  >  CLICK  >  LOW_TICK / THUD
高い ....................... 低い
```

`sharpness` はユーザーの強度設定でスケールしない。強さではなく**性格**を担うため。

### 2026-08-15 の調整

実機フィードバック「全体的に低く重い。周波数を上げたい」を受けて、
**Envelope の sharpness を全体的に引き上げ、プリミティブを CLICK から TICK へ寄せた。**

順序関係は保持している。`destructive` と `error` は意図的に低いまま据え置き、
他が上がったぶん**むしろ以前より際立つ**。

| トークン | T4 (Envelope) | T3/T2 (Primitive) | T1 (Predefined) | T0 (Waveform) |
|---|---|---|---|---|
| `selection` | S0.72; (.25,.72,8)→(0,.72,12) | TICK ×0.25 | `EFFECT_TICK` | 8ms / amp 40 |
| `navigation` | S0.58; (.35,.58,10)→(0,.55,18) | TICK ×0.45 | `EFFECT_TICK` | 10ms / amp 60 |
| `softConfirm` | S0.65; (.45,.65,10)→(0,.65,20) | TICK ×0.55 | `EFFECT_TICK` | 12ms / amp 80 |
| `send` | S0.90; (.70,.93,6)→(0,.79,22) | TICK ×0.85 | `EFFECT_CLICK` | 14ms / amp 150 |
| `threshold` | S0.93; (.90,1.0,5)→(0,.86,16) | **CLICK** ×0.75 | `EFFECT_CLICK` | 12ms / amp 200 |
| `thresholdRelease` | S0.79; (.45,.79,5)→(0,.72,14) | TICK ×0.40 | `EFFECT_TICK` | 10ms / amp 90 |
| `reaction` | S0.51; (.50,.62,12)→(.75,.79,10)→(0,.72,24) | QUICK_RISE ×0.40 + TICK ×0.65 | `EFFECT_CLICK` | 波形 |
| `success` | S0.65; (.40,.65,10)→(0,.65,12)→(0,.65,50)→(.70,.79,10)→(0,.72,18) | TICK ×0.50, +60ms TICK ×0.80 | `EFFECT_DOUBLE_CLICK` | 波形（上昇2連） |
| `warning` | S0.79; (.75,.79,8)→(0,.72,10)→(0,.72,90)→(.45,.65,10)→(0,.65,16) | TICK ×0.75, +100ms TICK ×0.50 | `EFFECT_DOUBLE_CLICK` | 波形（下降2連） |
| `error` | S0.58; (.80,.58,8)→(0,.55,8)→(0,.55,42)→(.80,.58,8)→(0,.55,8)→(0,.55,42)→(.60,.51,10)→(0,.51,14) | CLICK ×0.80 ×2, +50ms **T3:** LOW_TICK ×1.0 / **T2:** CLICK ×0.55 | `EFFECT_DOUBLE_CLICK` | 波形（詰まった3連） |
| `destructive` | S0.41; (.85,.44,18)→(0,.41,45) | **T3:** THUD ×0.90 / **T2:** CLICK ×0.90 + QUICK_FALL ×1.0 | `EFFECT_HEAVY_CLICK` | 35ms / amp 200 |

`threshold` だけ CLICK のまま残している。
唯一「硬く確定した」と感じるべき瞬間であり、
かつ同じ画面で隣り合う `send` と区別がつく必要があるため。

T-1（API 24-25、振幅制御なし）は duration のみで近似する。
この階層では表現力がほぼ無いため、**トークンの区別は視覚側で担保する**。

---

## 発火抑制ルール

| ルール | 理由 |
|---|---|
| OS の触覚設定が OFF なら一切発火しない | ユーザーの意思を尊重する |
| 省電力モード中は発火しない | 端末側の期待に合わせる |
| `selection` は最小間隔 40ms でスロットリング | 素早い移動で連打にならないように |
| `send` は最小間隔 120ms | 連続送信で振動が繋がらないように |
| ドラッグ中の連続発火は禁止 | `threshold` の 1 回だけが意味を持つ |
| アプリがバックグラウンドなら発火しない | 意図しない振動を出さない |

## ユーザー設定

`Settings → Haptics`

- **強度**: `OFF` / `SUBTLE` / `NORMAL`（既定） / `STRONG`
  intensity と primitive scale に係数を掛ける。**duration には掛けない**
  （長さを変えると意味が変わってしまうため）
- 端末が対応している Tier を表示する（透明性）

---

## スタンプと Expression の触覚

スタンプを単なる画像機能に閉じない。将来的に

```
Sticker + Animation + Haptic  =  Rinowa 独自の Expression
```

へ拡張できる設計とする。→ [STICKER_ARCHITECTURE.md](STICKER_ARCHITECTURE.md)

### 送信者は「意味」しか選べない

> **送信者が任意の振動パターンを受信者へ送りつけられる構造にしない。**

これは機能ではなく安全性の要件である。
振動時間と強度をユーザー生成コンテンツが直接指定できるなら、それは嫌がらせの道具になる。

送信側が選べるのは semantic type だけ。

```
CELEBRATION / LAUGH / LOVE / SURPRISE
```

**受信側クライアントが、自分の設定・OS・端末能力に応じてローカルの安全なパターンへ変換する。**
送られてくるのは「祝い」という意味であって、「800ms 最大強度」ではない。

### 受信時のふるまい

- **受信のたびに強い触覚を自動再生しない。** 画面を見ていない相手の手の中で勝手に強く震えるのは、通知ではなく妨害である
- アプリがバックグラウンドなら発火しない（既存の抑制ルールに従う）
- `Settings → Haptics` で Expression の触覚を OFF / 強度調整できるようにする
- 連続受信時はスロットリングする

### Prototype での扱い

Prototype 0 ではスタンプ送信時に既存の `send` が鳴るのみ。
Expression 専用トークンは、アニメーション表現を実装する段階で
このドキュメントへ追加してから実装する。**先に意味を決めてから値を作る。**

## Haptic Lab（開発用画面）

**Prototype 0 で最初に作る画面。** Human-in-the-loop 開発の中核。

- 全トークンを一覧し、個別に発火できる
- 各トークンについて、**この端末で実際に使われている Tier** を表示
- 端末の対応状況（`areEnvelopeEffectsSupported` / 対応プリミティブ / 振幅制御）を表示
- 強度設定を切り替えて即比較できる
- 2 つのトークンを連続再生して対比を確認できる（`success` ↔ `error` など）

開発者本人がこの画面で全トークンを触り、
「`destructive` が `send` と区別しづらい」のような指摘を返す。
その指摘を受けて、`HapticTokenSpec` の数値だけを変更する。

---

## 検討事項（実機テスト時に判断する）

- **`HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE` / `_DEACTIVATE`（API 34+）**
  スワイプ返信の threshold に、意味的には完全一致する OS 標準の定数がある。
  OS がその端末向けにチューニングした感触が得られる利点がある一方、
  自前の Tier 階層から外れるため一元的な調整ができなくなる。
  **実機で両方を比較して決める。** 現時点では自前実装を既定とする。
- 送信音（サウンド）を触覚と重ねるかどうか。重ねる場合、遅延の一致が必須。

---

## iOS 対応表（値ではなく意味の対応）

**Android の数値は移植しない。** iOS では以下を起点に、**改めて実機で調整する。**

| トークン | iOS 実装の起点 |
|---|---|
| `selection` | `UISelectionFeedbackGenerator.selectionChanged()` |
| `navigation` | `UIImpactFeedbackGenerator(style: .light)` intensity 0.5 |
| `softConfirm` | `UIImpactFeedbackGenerator(style: .soft)` |
| `send` | Core Haptics: transient, intensity 0.6 / sharpness 0.75 |
| `threshold` | `UIImpactFeedbackGenerator(style: .rigid)` intensity 0.9 |
| `reaction` | Core Haptics: continuous(短) + transient の 2 段 |
| `success` | `UINotificationFeedbackGenerator(.success)` |
| `warning` | `UINotificationFeedbackGenerator(.warning)` |
| `error` | `UINotificationFeedbackGenerator(.error)` |
| `destructive` | Core Haptics: transient, intensity 1.0 / **sharpness 0.2**（鈍く重く） |

`UIFeedbackGenerator` 系は `prepare()` を事前に呼ばないと初回に遅延が出る。
ジェスチャ開始時に `prepare()` する。
