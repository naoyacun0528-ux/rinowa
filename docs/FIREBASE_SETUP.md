# Firebase セットアップ

プロジェクト: **`echo-cfe37`**

Firebase CLI で自動化できる範囲は自動化しました。**残りは3つだけ**です。

---

## ✅ 完了（CLI で実施済み）

| 内容 | 詳細 |
|---|---|
| Android アプリ登録 ×2 | `blog.nextlab.echo` / `blog.nextlab.echo.debug` |
| SHA 指紋 ×4 | 各アプリに SHA-1 と SHA-256 |
| `google-services.json` | `android/app/` に配置（両アプリを1ファイルで網羅） |
| ビルド確認 | debug / release 両方が通ることを確認済み |

使ったコマンド（記録として）:

```bash
firebase apps:create ANDROID "Rinowa" --package-name blog.nextlab.echo --project echo-cfe37
firebase apps:android:sha:create <appId> <sha> --project echo-cfe37
firebase apps:sdkconfig ANDROID <appId> --project echo-cfe37
```

### アプリ ID

```
blog.nextlab.echo        1:321506749950:android:2355fca050d966786764af
blog.nextlab.echo.debug  1:321506749950:android:6cf4716d2c1d5e706764af
```

---

## ⬜ 残り3つ（コンソールでの操作が必要）

まず開く: <https://console.firebase.google.com/project/echo-cfe37>

---

### 1. サインイン方法を有効にする

左サイドバー **「構築」→「Authentication」**

1. 初回なら **「始める」** を押す
2. 上部タブ **「Sign-in method」** を開く

#### 1-A. メール / パスワード

3. **「新しいプロバイダを追加」**
4. **「メール / パスワード」** を選ぶ
5. 一番上のトグル **「有効にする」** を ON
6. **その下の「メールリンク（パスワードなしでログイン）」は OFF のまま**
   → 採用したのは「メール + パスワード + 確認メール」です。両方 ON にすると導線が2つになります
7. **「保存」**

#### 1-B. Google

8. もう一度 **「新しいプロバイダを追加」→「Google」**
9. トグルを **ON**
10. **「プロジェクトの公開名」** に `Rinowa` と入力
    → サインイン画面に「Rinowa にログイン」と出る名前です
11. **「プロジェクトのサポートメール」** で自分のアドレスを選ぶ（必須）
12. **「保存」**

> **なぜ CLI でできないか:** `firebase auth` はユーザーの一括入出力
> （`auth:export` / `auth:import`）しか持たず、プロバイダ有効化のコマンドがありません。
> Identity Platform 側の設定で、コンソールの管轄です。

---

### 2. Firestore データベースを作る

左サイドバー **「構築」→「Firestore Database」**

1. **「データベースの作成」**
2. **エディション** は **Standard**
3. **データベース ID** はそのまま（`(default)`）
4. **ロケーション** で **`asia-northeast1`（東京）** を選ぶ
5. **「本番環境モードで開始する」** を選ぶ ← ここ重要
6. **「作成」**

> **Enterprise を選ばないでください。** あれは MongoDB 互換 API の系統で、用途が違います。
> CLI のヘルプにも `--mongodb-compatible-data-access` は enterprise 専用と書かれています。
> Rinowa は Firebase の Android SDK をそのまま使うため Standard が前提の道であり、
> Security Rules もオフライン永続化も Standard を想定した仕組みです。
> CLI の既定値も `standard` です。

作成に1〜2分かかります。

> ### テストモードを選ばないでください
> 30日間「**誰でも全データを読み書きできる**」状態になります。
> [PRIVACY_PRINCIPLES.md](PRIVACY_PRINCIPLES.md) と正面から衝突します。
>
> 本番モードは最初「誰も読み書きできない」状態です。それが正しい出発点で、
> 必要な穴だけを Security Rules で開けます。ルールは私が書いて反映します。

> **ロケーションは後から変更できません。** 東京以外にする理由が無ければ `asia-northeast1` で。

> **なぜ CLI でできないか:** `firestore:databases:create` は存在しますが、
> プロジェクトで **Cloud Firestore API が未有効**のため 403 で止まります。
> ```
> Cloud Firestore API has not been used in project echo-cfe37 before or it is disabled.
> ```
> コンソールで作成すると API 有効化も同時に行われるので、一手で済みます。

---

### 3. Cloud Storage を作る（止まってよい）

左サイドバー **「構築」→「Storage」→「始める」**

1. ルールの選択で **「本番環境モードで開始」**
2. ロケーションは Firestore と同じ **`asia-northeast1`**
3. **「完了」**

> ### 「Blaze プランにアップグレード」と出たら、そこで止めてください
>
> 新しい Firebase プロジェクトでは、Cloud Storage の利用に
> **従量課金プラン（Blaze）への切り替え**を求められることがあります。
> これは**クレジットカードの登録**を伴います。
>
> - **保護者の判断が必要な話です。** 勝手に進めないでください
> - 私は支払い情報を扱いません
>
> **Storage が無くても Prototype 1 は進められます。**
> 必要になるのはカスタムスタンプをクラウドへ置く段階だけで、
> 認証・アカウント・メッセージングは Firestore だけで動きます。
> その場合は「スタンプは端末内のみ」として先に進め、Storage は保留にします。

> **なぜ CLI でできないか:** バケットの新規作成コマンドが Firebase CLI にありません
> （`firebase init storage` はルールの設定のみ）。

---

## 終わったら

**「1と2が終わった」と伝えてください。** CLI で確認します。

```bash
firebase firestore:databases:list --project echo-cfe37
```

3 が Blaze 要求で止まった場合も、そう伝えてもらえれば
スタンプをローカル限定にしたまま先へ進めます。

---

## この先、私が CLI でやること

2と3が済めば、以降はコマンドで進められます。

```bash
firebase deploy --only firestore:rules,storage --project echo-cfe37
```

- Security Rules の作成と反映
- Firestore インデックスの定義
- App Check（Prototype 2）

`firebase login` は**すでに済んでいます**（naoyacun0528@gmail.com）。
**私がパスワードや認証情報を扱うことはありません。**

---

## 決めたこと

**認証方式: Google サインイン + メール（確認メール付き）+ パスワードの2本立て。**

根拠は [SYNC_AND_BACKUP.md](SYNC_AND_BACKUP.md) の原則。

> **Device can be replaced. Account persists.**

匿名認証は復旧手段が一切なく、端末を失うとトークも友達も消えるため採用していません。

**確認メールを必須にする理由:** 未確認のメールアドレスは、他人のアドレスで登録できてしまいます。
パスワード再設定はそのアドレスに届くため、確認しないまま進むと
**アカウントの持ち主が確定しません。**

---

## 秘密の扱い

`google-services.json` は gitignore 済みで、履歴にも入っていません。

中の `api_key` は**秘密ではありません** — どの APK からも取り出せる公開値で、
Firebase の保護は API キーではなく **Security Rules と App Check** で行います。
それでも不用意に配らないよう、リポジトリとソース zip の両方から除外しています。
