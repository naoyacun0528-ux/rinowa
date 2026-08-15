# Firebase セットアップ — あなたにしかできない作業

Firebase コンソールの操作は**あなたの Google アカウントの権限**が必要なため、
Claude では実行できません。ここに列挙した5つを済ませてください。所要は10分程度です。

プロジェクト: **`echo-cfe37`**

---

## 1. リリース用のアプリを登録する（必須・今すぐ）

現在の `google-services.json` には **`blog.nextlab.echo.debug` しか入っていません。**
そのため **リリースビルドが失敗します**（実際に失敗を確認済み）。

```
File google-services.json is missing.
Searched: app\src\release\google-services.json, app\google-services.json ...
```

**手順**

1. Firebase コンソール → プロジェクトの設定 → 「アプリを追加」→ Android
2. パッケージ名に **`blog.nextlab.echo`** を入力（`.debug` なし）
3. 登録後、`google-services.json` を**ダウンロードし直す**
   → 2つのアプリが両方入った1つのファイルになります
4. そのファイルを私に渡してください

---

## 2. SHA 指紋を登録する（Google サインインに必須）

Google サインインは、アプリの署名を照合します。**登録しないとサインインできません。**

プロジェクトの設定 → 各アプリの「SHA 証明書フィンガープリント」→「フィンガープリントを追加」

### `blog.nextlab.echo.debug`（開発用）

```
SHA-1    01:96:CF:E1:8D:CC:20:F9:48:CD:C5:C3:51:39:56:20:9A:23:50:23
SHA-256  81:A8:67:26:6D:4E:23:98:40:04:E0:AA:CE:32:F9:13:7D:5B:20:44:3B:84:52:E4:C1:BC:5D:46:2B:96:58:02
```

### `blog.nextlab.echo`（配布用）

```
SHA-1    8E:7B:1B:D2:BA:AF:B9:73:82:CF:8A:1D:A5:D6:36:89:05:3C:56:E7
SHA-256  53:15:50:1D:D0:83:20:A3:5E:A8:41:B4:EE:48:B7:C5:CB:56:27:E1:BF:FA:67:D4:49:35:91:AD:C4:38:5F:F9
```

**これらは秘密ではありません。** どの APK からも取り出せる公開情報です。
秘密は署名鍵そのもの（`echo-release.jks`）で、それは → [SIGNING.md](SIGNING.md)

> **注意:** 開発用の指紋は `~/.android/debug.keystore` のものです。
> この PC を変えると変わるので、そのときは登録し直しが要ります。

---

## 3. サインイン方法を有効にする

Authentication → Sign-in method

| 有効にするもの | 用途 |
|---|---|
| **Google** | ワンタップでのサインイン |
| **メール / パスワード** | Google を使わない人向け |

**メールリンク（パスワードなしログイン）は有効にしないでください。**
今回採用したのは「メール + パスワード + 確認メール」です。

---

## 4. Firestore を作る

Firestore Database → データベースの作成

- ロケーション: **`asia-northeast1`（東京）** を推奨。友人が国内なら遅延が最小になります
- モードは **本番環境モード**（ルールは私が書いて `firebase deploy` で反映します）

> **テストモードを選ばないでください。** 30日間「誰でも全データ読み書き可能」になります。
> [PRIVACY_PRINCIPLES.md](PRIVACY_PRINCIPLES.md) と真正面から衝突します。

## 5. Cloud Storage を作る

Storage → 開始

- ロケーションは Firestore と揃える
- こちらも**本番環境モード**

スタンプの Master Asset 置き場になります → [STICKER_ARCHITECTURE.md](STICKER_ARCHITECTURE.md)

---

## Claude 側でやること（あなたの作業不要）

- Security Rules の作成と `firebase deploy --only firestore:rules,storage` での反映
- 認証画面と、確認メールが済むまで先へ進ませない制御
- アカウントのデータモデルと復元処理
- App Check（Prototype 2 で）

`firebase login` は**あなたのアカウントでの認証**なので、
デプロイが必要になった時点で、あなたに実行をお願いします。
**私がパスワードや認証情報を扱うことはありません。**

---

## 決めたこと

**認証方式: Google サインイン + メール（確認メール付き）+ パスワードの2本立て。**

理由は [SYNC_AND_BACKUP.md](SYNC_AND_BACKUP.md) の原則。

> **Device can be replaced. Account persists.**

匿名認証は復旧手段が一切なく、端末を失うとトークも友達も消えるため採用していません。

**確認メールを必須にする理由:** 未確認のメールアドレスは、他人のアドレスで登録できてしまいます。
パスワード再設定はそのアドレスに届くため、確認しないまま進むと
**アカウントの持ち主が確定しません。**
