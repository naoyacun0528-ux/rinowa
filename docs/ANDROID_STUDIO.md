# Android Studio、押す場所だけ

日本語化のプラグインは **2024年7月で更新が止まっていて**、今入っている版
（build 261）に対応するものが無い。英語のまま使うしかないので、
**実際に押すものだけ**をここに書く。全部は覚えなくていい。

入っている場所:

```
C:\Users\yukii\AppData\Local\Programs\android-studio\bin\studio64.exe
```

スタートメニューに「Android Studio」がある。

---

## 最初の1回

プロジェクトを開くと **Trust and Open Project?**（このプロジェクトを信用して
開くか）と訊かれる。**Trust Project** を押す。

自分のコードなので信用していい。この確認は、他人から貰ったプロジェクトが
開いた瞬間にビルドスクリプトを走らせるのを防ぐためにある。

開く場所は `C:\dev\echo\android`。**`C:\dev\echo` ではない**（Gradle の
設定ファイルが android の下にある）。

そのあと下のバーで **Gradle Sync** が走る。初回は数分かかる。

---

## 端末を繋ぐ

Profiler も Layout Inspector も、**端末が繋がっていないと何も出ない**。

1. 端末の「設定 > デバイス情報 > ビルド番号」を7回叩いて開発者向けオプションを出す
2. 「開発者向けオプション > USB デバッグ」を入れる
3. USB で繋ぐ。端末に出る確認は **許可**

繋がったかどうかは、右上のドロップダウンに端末の名前が出るかで分かる。
コマンドで確かめるなら:

```
C:\Android\Sdk\platform-tools\adb.exe devices
```

---

## 覚える英語は5つだけ

| 画面に出る英語 | 何をするもの |
|---|---|
| **Run** ▶ | 端末に入れて動かす |
| **Profiler** | 速さ・メモリ・通信を見る |
| **Layout Inspector** | 画面の中身と、**再描画の回数**を見る |
| **Logcat** | 端末が吐いているログ |
| **Build Variants** | debug と release の切り替え |

どれも上のメニュー **View > Tool Windows** の下にある。

---

## いちばん使うもの — 再描画の回数

「なんかカクつく」を「この部品が1フレームで40回描き直されている」に
変えるためのもの。

1. アプリを **Run** で動かす
2. **View > Tool Windows > Layout Inspector**
3. 左上で動いているアプリを選ぶ
4. **Recomposition Counts** を入れる

数字が異常に大きい部品が犯人。**その名前を伝えてくれれば直せる。**

Compose は「変わったところだけ描き直す」のが建前で、実際には
「変わっていないのに描き直している」がよく起きる。目では分からないが、
この数字には出る。

## その次 — 引っかかりを探す

1. **View > Tool Windows > Profiler**
2. 動いているアプリを選ぶ
3. **CPU** の帯を見る

1フレームは **16.6ミリ秒**。これを超えた回が赤く出る。
スクロールしながら赤が出る場所が、指に引っかかりとして伝わっている場所。

---

## 出来ないこと

**エミュレータは、この PC では動かない。**

```
$ sdkmanager --install emulator
Warning: Failed to find package 'emulator'
```

Surface が Snapdragon X（ARM64）で、Google は Windows/ARM64 向けの
エミュレータを配っていない。Android Studio の ARM64 版も無い。
**メモリを増やしても変わらない。**

複数の端末が要る試験（E2EE の鍵交換など）は:

- 手持ちの実機3台（Pixel 10 / arrows We2 / Galaxy A23 5G）
- GitHub Actions の Ubuntu ランナー（`.github/workflows/android.yml` の末尾を参照）

なお、この Android Studio 自体も x64 版なので ARM の上でエミュレーションで
動いている。**IDE はもっさりする。** Profiler と Layout Inspector が見せるのは
端末から来たデータなので、そちらの精度には影響しない。
