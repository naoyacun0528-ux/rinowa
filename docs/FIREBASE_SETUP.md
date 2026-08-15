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
firebase apps:create ANDROID "Echo" --package-name blog.nextlab.echo --project echo-cfe37
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

CLI では**できません**。理由も併記します。

### 1. サインイン方法を有効にする

Authentication → Sign-in method で次の2つを有効化。

| 有効にするもの | 用途 |
|---|---|
| **Google** | ワンタップでのサインイン |
| **メール / パスワード** | Google を使わない人向け |

**メールリンク（パスワードなしログイン）は有効にしないでください。**
採用したのは「メール + パスワード + 確認メール」です。

> **なぜ CLI でできないか:** `firebase auth` はユーザーの一括入出力
> （`auth:export` / `auth:import`）しか持たず、プロバイダの有効化コマンドがありません。
> ここは Identity Platform 側の設定で、コンソールか別 API の管轄です。

### 2. Firestore データベースを作る

Firestore Database → データベースの作成

- ロケーション: **`asia-northeast1`（東京）**
- モード: **本番環境モード**

> **テストモードを選ばないでください。** 30日間「誰でも全データ読み書き可能」になります。
> [PRIVACY_PRINCIPLES.md](PRIVACY_PRINCIPLES.md) と正面から衝突します。
> ルールは私が書いて `firebase deploy` で反映します。

> **なぜ CLI でできないか:** `firestore:databases:create` は存在しますが、
> プロジェクトで **Cloud Firestore API がまだ有効化されていない**ため 403 で止まります。
> ```
> Cloud Firestore API has not been used in project echo-cfe37 before or it is disabled.
> ```
> コンソールで作成すると API 有効化も同時に行われるので、そちらが一手で済みます。

**ロケーションは後から変更できません。** 東京以外にする理由が無ければ `asia-northeast1` で。

### 3. Cloud Storage を作る

Storage → 開始

- ロケーションは Firestore と揃える
- **本番環境モード**

スタンプの Master Asset 置き場になります → [STICKER_ARCHITECTURE.md](STICKER_ARCHITECTURE.md)

> **なぜ CLI でできないか:** バケットの新規作成コマンドが Firebase CLI にありません
> （`firebase init storage` はルールの設定のみ）。

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
