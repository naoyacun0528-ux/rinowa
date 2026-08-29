# 端末IDが入れ替わって、過去が読めなくなった

2026-08-29。実機で見つかった。**新規の利用者は踏まない**が、踏んだ端末では
過去のメッセージと写真が永久に読めなくなる。記録しておく価値があるのは、
原因が「暗号の設計」ではなく**設定ファイルの改名**だったから。

## 症状

- 会話がすべて「まだ開けません」
- 安全画面（会話の名前を押して開く）に端末が1つも出ない
- サムネイルは出るのに、押しても写真が開かない。動画は再生すると真っ黒

## 原因

アプリの名前を Echo → Rinowa に変えたとき、設定ファイルも改名した。
移行の関数（`renamedPreferences`）を用意して3箇所に入れたが、**1箇所忘れた。**

```
KnownDevices        renamedPreferences("rinowa_known_devices", "echo_known_devices")  ✓
SettingsRepository  renamedPreferences(FILE, FORMER_FILE)                             ✓
PushTokenRegistrar  renamedPreferences(PREFS, FORMER_PREFS)                           ✓
CryptoEngine        getSharedPreferences("rinowa_crypto", MODE_PRIVATE)               ← 忘れた
```

端末IDはこの設定ファイルに入っている。新しいファイルは空なので、
**`CryptoEngine` は「まだ端末IDが無い」と判断して新しく作った。**

一方、鍵の保管庫はディレクトリ名が**利用者ID**（`filesDir/crypto/<uid>`）なので、
改名の影響を受けずにそのまま残った。中身は古い端末のもの。

結果、保管庫を開こうとして拒まれる:

```
CryptoStore: the account in the store doesn't match the account in the constructor:
  expected @uid:lowan.local:KXTCPHYMEC,   ← 保管庫の中身
  got      @uid:lowan.local:JTTAJOOOVT    ← こちらが渡した値
```

**暗号エンジンがまるごと開かない。** だから全部が「まだ開けません」になる。

## なぜ写真まで巻き込まれたか

写真は保管庫（Firebase Storage）にあり、**開く鍵はメッセージの中**にある。
メッセージが開けなければ鍵も取り出せない。ダウンロードは成功していて、
ハッシュも合っていたのに、開くところで落ちていた:

```
decrypt failed
Caused by: javax.crypto.AEADBadTagException: BAD_DECRYPT
```

「サムネイルは出るのに本体が出ない」のは、**サムネイルがメッセージ本体に
埋まっている**（32px・上限6KB）から。あれは封の外にある。

## 直したこと

### 1. 忘れていた移行を足した

```kotlin
val prefs = context.renamedPreferences(PREFS, FORMER_PREFS)
```

### 2. 食い違ったら保管庫のほうを信じる

移行を足しても、**移行より前に一度でも起動してしまった端末は救われない**
（新しいファイルが空でなくなっているので、移行が走らない）。

そこで、開けなかったときにエラー文から保管庫の端末IDを読み取り、
そちらを採用して覚え書きを書き換える。

```kotlin
val recovered = deviceIdIn(failure.message) ?: throw failure
prefs.edit().putString(KEY_DEVICE_ID, recovered).commit()
open(me, recovered, store)
```

**秘密鍵を持っているのは保管庫で、設定ファイルはただの覚え書き。**
食い違ったとき、覚え書きを信じて作り直すと、その端末が過去に受け取ったものを
二度と開けなくなる。

なお、この修復は最初 `expected` と `got` を逆に読んでいて、**渡した値をそのまま
採用し直すという何もしない修復**になっていた。実機のログに
`prefs: rinowa=JTTAJOOOVT` と `got JTTAJOOOVT` が並んで、ようやく気づいた。
**実機で確かめずに報告していたら、直っていないものを直ったと言っていた。**

### 3. 黙って false を返していた3箇所を喋らせた

`MediaRepository.fetch` は、取れなかったとき理由を言わずに `false` を返す道が
3つあった。画面には「押しても何も起きない」としか出ない。

## 直せないこと

**失われた鍵は戻らない。**

端末IDが変わった時点で、この端末は Matrix から見て**別の端末**になった。
会話の鍵は「ここから先が読める」という形で配られるので、新しい端末に来たのは
その時点より後のぶんだけ。ログにそのまま出ている:

```
Megolm: The message was encrypted using an unknown message index,
        first known index 1, index of the message 0
```

**index 0 が読めない。持っているのは index 1 から。**

E2EE なので、これは仕様どおり。鍵が無いものは誰にも開けない。開けたら、
鍵を持たない誰かでも開けるという意味になる。

戻す道があるとすれば:

- **もう1台の端末**に鍵が残っていれば、そちらでは読める
- **ドライブのバックアップ**（`docs/SYNC_AND_BACKUP.md`）。あれは封を開けた
  状態で保存してあるので、復元すれば戻る

## 新規の利用者は踏まない

初回起動では:

1. `rinowa_crypto` が空 → 新しい端末IDを作る
2. 保管庫もそのIDで作られる
3. **最初から一致している**

旧名の `echo_crypto` は存在しないので、移行は何もしない。

踏むのは**改名をまたいで使い続けた端末だけ**で、それも次の起動で自己修復する。

## 教訓

**設定ファイルを改名するときは、全部の呼び出し元を数える。**

3箇所直して1箇所忘れた。忘れた1箇所が、たまたま**暗号の身元**を持っていた。
他の3つ（既知の端末・設定・push トークン）なら、失われても取り直せる。

そして**移行を書いた時点で、書き忘れを機械が見つけられるようにすべきだった。**
`getSharedPreferences` を直接呼んでいる箇所を数える検査があれば、その場で出た。
`SwallowedErrorTest` と同じ種類の検査で、書ける。
