# Lowan 0.18.5

**開発用の画面を、製品のビルドから外しました。** ついでに消せるものを全部消しました。

## 製品ビルドに入らなくなったもの

触覚 Lab と Direct Lab、その裏にある Direct の通信路、NetworkProbe、Rinowa の計測。
合わせて **2,649行**（コード全体の9%）。実体は `src/debug` にしか無く、release には
同じ名前の空の版だけが残ります（`ui/lab/DevSurfaces.kt`）。一覧と設定にあった
入口も出なくなりました。

クラス単位で確認しました。

| | release | debug |
|---|---|---|
| HapticLabScreen | 0 クラス | 13 |
| Direct の通信路 | 0 | 48 |
| DirectLab / NetworkProbe / RinowaBenchmark | 0 | あり |

Nearby のライブラリも `debugImplementation` にしました。誰も呼ばない探索用の SDK を
製品のビルドに積む理由が無いので。

## 権限が 32 → 21 に減りました

Direct のために宣言していた権限が、Direct の無いビルドから消えます。

消えたもの: Bluetooth 5種、NEARBY_WIFI_DEVICES、ACCESS_FINE_LOCATION、
ACCESS_LOCAL_NETWORK、CHANGE_WIFI_STATE。

**広告 ID の2つも外しました**（`ACCESS_ADSERVICES_AD_ID`、`AD_ID`）。Firebase の計測
ライブラリが自分のマニフェストで足してくるものです。収集はもともと切ってありましたが、
「切ってある」と「要求していない」は別の話で、ストアの権限一覧に出るのは後者です。

握り潰しの検査（SwallowedErrorTest）は debug 側のソースも見るようにしました。
移した先が検査の外に出ないように。

## 消したもの

- 使っていない import 21個
- 通話の記録をタップしてかけ直す機能を消したときに残っていた `val haptics`
- `CryptoEngine.dispatch` の到達しない `else`。無いほうが良い —— Request に種類が
  増えた日に、黙って false を返すのではなくコンパイルが止まる
- LowanApp で2回書いていた同じキャスト、余分な `Unit` 2つ、不要な安全呼び出し

**コンパイラの警告は0件になりました**（非推奨 API を除く）。全モジュールのテスト通過。

## まだ実機で確認していません

端末が2台とも外れているので、この版はビルドとテストだけです。次に繋いだときに、
一覧の Lab の入口と設定の「Lowan Direct（検証中）」が release で消えていることを
確認します。

0.18.4 の内容は `outputs/0.18.4/RELEASE-0.18.4.md` を参照。
