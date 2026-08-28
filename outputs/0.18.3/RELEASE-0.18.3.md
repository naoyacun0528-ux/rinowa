# Lowan 0.18.3

**中身の Echo を Lowan にしました。** 表に出る文字はすでに全部 Lowan でしたが、コードの
中と成果物のファイル名が Echo のままでした。

## 何が変わったか

- 成果物が `lowan-0.18.3-release.apk` などになりました（`echo-…` から）
- クラス名 `EchoDb` `EchoTheme` `EchoDimens` `EchoMotion` ほか約700箇所 → `Lowan…`
- ファイル14個の名前（`EchoTheme.kt` → `LowanTheme.kt` など）
- ドキュメント・コメント・`firestore.rules` の地の文の「Echo」
- 端末内の設定ファイル `echo_settings` `echo_push` → `lowan_settings` `lowan_push`
- Direct の探索名 `_echodirect._tcp.` → `_lowandirect._tcp.`、通話内部のトラック名

## 設定は引き継ぎます

設定ファイルの名前を変えるだけだと、**中身は黙って消えます**。触覚の設定と、端末を識別する
device id が入っているところです。device id を忘れた端末は通知が来なくなるのではなく、
**2つ目の登録を作り**、古いほうにも送られ続けます。

新しい名前で最初に読むときに、古いファイルを丸ごと移して空にします（`RenamedPrefs.kt`）。
0.18.2 で「写真をオリジナルでも送る」を入れた端末を 0.18.3 に上げて、入ったままである
ことを実機で確認しました。

## 変えなかったもの

- `applicationId = "blog.nextlab.echo"` — Android から見たアプリの正体。変えると再
  インストールと Firebase 再設定が必要で、いまの Drive バックアップも読めなくなります
- `https://echo.nextlab.blog/…` — 誰にも見えないうえ、移すと既存ビルドが動かなくなる
- Firebase プロジェクト ID `echo-cfe37` — Google の仕様で変更不可
- `blog.nextlab.echo.*` で始まる Intent の名前 — パッケージ名に付いている名前なので、
  パッケージを据え置くなら揃っているのはこちら

## Direct を使う場合の注意

探索名が変わったので、**0.18.2 以前とは互いを見つけられません**。Lowan Direct は検証中の
機能で、両方の端末を 0.18.3 にすれば元どおりです。

## 確認したこと（実機）

- We2 → Pixel にメッセージが届き、復号されて未読1が付いた
- 設定（オリジナル送信）が 0.18.2 → 0.18.3 で残った
- 単体テストは全部通っています

0.18.2 の内容は `outputs/0.18.2/RELEASE-0.18.2.md` を参照。
