# NEON GLASS UI

NEON INSTINCT 1.4.0 のガラス表現を、他のアプリで使える形に切り出したものです。11ファイル、約2,700行。**NEON INSTINCT のコードには一切依存していません**（切り出した状態で `javac` に通してあります）。

## これは何をするか

パネル・ボタン・数字などの表面を「ガラスの板」として描きます。板の**縁が背後の背景を屈折させ**、面には斜めの光沢と光源方向のにじみが乗ります。中央は透明のままなので、板の上の文字は歪みません。

画面の取り込み（ビットマップへのコピー）は**しません**。背景を描いている `Shader` オブジェクトそのものをガラスへ渡して評価させるため、毎フレームの読み戻しが発生せず、背景を変えるとガラスが自動的に追従します。

## 動作条件

| 項目 | 値 |
|---|---|
| minSdk | 29（Android 10）以上 |
| 屈折が効く条件 | **API 33（Android 13）以上** かつ ハードウェア描画 かつ 面の短辺が72px以上 |
| 依存 | `androidx.annotation`（`@RequiresApi` のため）のみ |

条件を満たさない場合は**塗りの表現へ自動的に退避**します。クラッシュしません。屈折が消えるだけで、ガラスらしい見た目は残ります。シェーダーのコンパイルに失敗した端末では `Log.w("NEON_GLASS", ...)` が1度だけ出ます。

## 組み込み（4ステップ）

### 1. パッケージ名を合わせる

現在 `com.neonglass` です。変えるなら11ファイルの1行目を置換してください。

```bash
sed -i 's/^package com\.neonglass;$/package jp.echo.android.glass;/' src/com/neonglass/*.java
```

ディレクトリも合わせて移動してください。

### 2. 背景を置く

`LiquidBackdropView` を画面の**一番下**（他のすべてのViewの後ろ）に敷きます。これがガラスに渡す背景そのものになります。

```java
FrameLayout root = new FrameLayout(this);
LiquidBackdropView backdrop = new LiquidBackdropView(this);
root.addView(backdrop, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
// この上に、アプリの中身を載せる
root.addView(content, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
```

`LiquidBackdropView` は自分が描いたグラデーションを `NeonGlassScene` へ渡します（`scene.publish(...)`）。**これを置かないと屈折の元になる背景がなく、ガラスは塗りの表現になります。**

背景の基準色も伝えてください。ガラスが「背景の下地は何色か」を知るために使います。

```java
NeonGlassScene.current().setBaseColor(Color.rgb(6, 11, 26));
```

### 3. ガラスを貼る

面にしたい View の背景に `LiquidGlassDrawable` を設定します。

```java
// primary / secondary は縁に乗るアクセント2色
// radius は角丸（px）
// strong は主役級の面なら true、控えめな面なら false
button.setBackground(new LiquidGlassDrawable(
        Color.rgb(50, 224, 255), Color.rgb(167, 255, 49), dp(18), true));
```

第5引数に `true` を渡すと「ゲーム中の面」として扱われ、`NeonGlassTuning` の GAME ダイヤルの倍率がかかります。メッセンジャーなら**使わなくて構いません**（4引数の方を使ってください）。

### 4. 触ったときの反応

`NeonSurfaceController` が押下時の縮み・光源の追従・離したときの跳ね返りを担当します。

```java
NeonSurfaceController surfaces = new NeonSurfaceController(
        context,
        () -> hapticsAreOn(),        // 触覚を鳴らすかどうか。常に false でも可
        backdrop,                    // 背景。null 可（その場合は波紋が出ません）
        Color.rgb(50, 224, 255), Color.rgb(167, 255, 49));

surfaces.installPressMotion(button, true, false);
```

View に `setTag("action_surface")` などの文字列タグを付けて `decorateTaggedSurfaces(root, true)` を呼ぶと、木を歩いて一括で貼ることもできます。認識するタグは `action_surface` / `glass_panel` / `drawer_panel` / `drawer_surface` / `setting_surface` / `gauge_surface` で、それぞれ角丸と強さが違います。

## ファイルの役割

| ファイル | 役割 | 外から使うか |
|---|---|---|
| `LiquidGlassDrawable` | ガラスの面そのもの。**これが本体** | ○ |
| `LiquidBackdropView` | 背景。ガラスに渡すShaderを作る | ○ |
| `NeonGlassScene` | 背景とガラスの受け渡し口 | ○ |
| `NeonSurfaceController` | 押下・離す・一括適用のアニメーション | ○ |
| `NeonGlassTuning` | 見た目の調整値と4プリセット | ○ |
| `NeonGlassInk` | 文字そのものをガラス材質で描く（`TextView` 用） | ○ |
| `NeonGlassGauge` | 進捗バー。溝に光が溜まる形 | ○ |
| `ForgeSlider` | 調整用スライダー。UIを作らないなら不要 | △ |
| `NeonGlassShader` | AGSLシェーダー。屈折の本体 | × |
| `GlassProfile` | ベベル断面の数学 | × |
| `DisplayRefreshRate` | パネルの実リフレッシュレート追従 | × |

下3つは内部用です。触る必要はありません。

## 調整値

`NeonGlassTuning` に10個のダイヤルがあり、4つのプリセットがあります。

```java
NeonGlassTuning.current().load(context);                       // 保存値を読む
NeonGlassTuning.Preset.QUIET.applyTo(NeonGlassTuning.current()); // 控えめに振る
```

| プリセット | 性格 |
|---|---|
| `STANDARD` | 設計どおりの釣り合い |
| `CLEAR` | 素材を薄く、後ろをよく通す |
| `DEEP` | 厚みのある板。面も縁も強く光る |
| `QUIET` | 主張を抑えた静かなガラス |

**メッセンジャーなら `QUIET` か `CLEAR` から始めるのを勧めます。** NEON INSTINCT はゲームなので既定値が派手めです。文字を読む時間が長いアプリでは、面の光沢（SHEEN）と光のにじみ（BLOOM）を下げたほうが読みやすくなります。

主なダイヤル:

| 名前 | 意味 | 既定 |
|---|---|---|
| `SHEEN` | 面を斜めに走る光沢 | 60% |
| `BLOOM` | 光源側のにじみ | 60% |
| `DENSITY` | 素材の濃さ。**下げるほど背景が濃く出る** | 70% |
| `GAME` | ゲーム面だけにかかる倍率 | 100% |
| `SPECULAR` / `REFLECT` | 縁の輝きと反射 | 0.95 / 0.62 |
| `BEVEL` / `REACH` / `SPLIT` | ベベルの幅・屈折の距離・色収差 | — |
| `OPACITY` | いちばん外側の不透明度 | 0.88 |

調整値は専用の `SharedPreferences`（ファイル名 `neon_glass_tuning`）に保存されます。**アプリ本来のデータとは別ファイル**なので、調整をいじってもユーザーのデータには触れません。

## 知っておくべき落とし穴

**1. 小さい面では屈折が効きません。** 短辺が72px（密度420の端末で約27dp）を下回ると、面が自分のベベルより小さいため塗りへ退避します。細いバーや小さなチップにガラスを持たせたいときは、**その要素の背景として大きめの面を持たせてください。** 別のViewで包むと、元のViewを直接制御しているコード（`setVisibility` など）が包んだ側に届かず、要素を隠したのに面だけ残るという事故が起きます。NEON INSTINCT で2回起きました。

**2. `NeonGlassScene` はプロセス全体で1つです。** 複数のActivityがある場合、背景を持つ画面が切り替わるたびに `publish` されます。背景を持たない画面ではガラスが塗りに退避します。

**3. 調整を変えたら再描画が要ります。** ダイヤルを動かしても、面は自分から描き直しません。`view.invalidate()` を木全体に流してください（`NeonSurfaceController` を使っている場合も同じ）。

**4. GPU時間を測ってください。** NEON INSTINCT では中央値9ms（120Hzの1フレームは8.3ms）まで来ています。面を増やすほど上がります。

```bash
adb shell dumpsys gfxinfo <applicationId> reset
# 8秒ほど操作する
adb shell dumpsys gfxinfo <applicationId> | grep -E "Janky|gpu percentile"
```

## 出どころ

NEON INSTINCT 1.4.0（commit `cd8db32456c630f32798c4c86b9f6ceb7a66c547`）から切り出しました。ClaudeとCodexが同じ課題に独立して取り組み、双方の実装を統合した版です。縁の屈折・鏡面・色収差はシェーダー側、面の光沢とにじみはCanvas側で、別々に作られたものが1つになっています。

検証: `javac`（Android API 37 + androidx.annotation 1.10.0）でコンパイル確認済み。**実機での動作確認はNEON INSTINCTとしてのみ行っており、切り出した状態での実行確認はしていません。** 最初の組み込みで背景が出ない・ガラスが塗りのままになる場合は、上の「2. 背景を置く」を見直してください。
