# いまどこにいるか

<!-- stamp:version -->
**0.20.5 - 2026-09-05**
<!-- /stamp:version -->

この行は `tools/release.ps1` が書き込みます。**手で書かないでください。**
前は手書きで、同じファイルの中に「現在 0.10.0」「0.12.4 公開済み」「通話履歴は 0.14.0 で完了」
が並び、実物は 0.17.1 でした。**セッションをまたいで状況が分かるように**という
この文書の目的が、そこで消えていました。数字の出どころは
`android/app/build.gradle.kts` の `versionName` 一箇所だけです。

以下は「どこまで済んで、次に何をするか」だけを書きます。設計の理由は各ドキュメントに。

---

## 直近の状態

**RINOWA SIGIL が実機で動いています。** Rinowa の封の仕組みの名前です。
中身は Olm / Megolm（`org.matrix.rustcomponents:crypto-android`）。
Pixel 10 ⇄ arrows We2 の release ビルドで送受信を確認済み。

**適用範囲の正本は `docs/RINOWA_SIGIL.md`。** 何が守られていて何が守られていないかは、
必ずあの表を見てください。研究の記録は `docs/RESEARCH_E2EE.md`。

**まだ「相手が本人だ」とは言えません。** 鍵の検証UIが無く、
`onlyAllowTrustedDevices = false` のままです。ROADMAP の「鍵の検証フェーズ」。

封をする単位は**メッセージの中身まるごと**です（`data/MessageEnvelope.kt`）。
本文だけ暗号化して写真の ID・寸法・サムネイルが外に出ていれば、
それは「写真の説明文だけ暗号化して写真は平文で送る」ことになります。

**写真はサーバー保管（XServer）+ 暗号化に移行済み。** 実機確認済み。
ファイルごとの乱数鍵で Tink の AES-GCM-HKDF-STREAMING、鍵は封筒の中。
**サーバーを運用している人間にも読めません。** → `docs/MEDIA_ARCHITECTURE.md` §11

**通話の SDP と ICE を封筒に入れました。** 実装済み・**実機未検証**。
WebRTC は相手を SDP の DTLS 指紋で決めるので、平文で置いていた間は
書き換えた者と暗号化された通話を確立できました。ICE には双方の IP も入っていました。

**動画（720p 変換 + ストリーミング再生）は実装済み・実機未検証。**

---

## 次にやること

| # | 項目 | 状態 |
|---|---|---|
| 1 | 動画の送受信を実機で通す | ビルド済み。端末が空き次第 |
| 2 | 通話の発着信を実機で通す（封筒を入れた後） | 同上 |
| 3 | Google ドライブ + PIN のバックアップ | **手動は実装済み**（単体テスト11本）。実機未確認 |
| 3b | 毎日の自動バックアップ | 暗証番号を端末に保存するか判断が要る。RESEARCH_E2EE §3.2 |
| 4 | mipmap-* の旧アイコン（Android 7 のみ） | 残置 |
| 5 | Yosegi の再計測（ビルド種別を記録して） | W-2/W-3 |

---

## 配布

**固定URL。毎回変わりません。**

```
https://echo.nextlab.blog/dly5sfc4x1/download.php?f=echo-latest-release.apk
```

リリースノートは `?f=RELEASE-latest.md`。
公開手順は `tools/publish.sh <version>` の一発。**URL を作り直さないこと。**

**配るのは release ビルドだけです。** debug ビルドは `android:debuggable` が立っていて、
入れた端末では任意のプロセスにデバッガを繋げます。手元の検証用には作りますが、
**固定URLからは配りません**（`tools/publish.sh` がそうしないようになっています）。

---

## 直近で終わったこと

### 通信基盤の研究 → Yosegi v1 凍結 → 実装（未投入）

| | |
|---|---|
| 仕様 | `docs/YOSEGI_V1_SPEC.md`（**凍結済み。変更せず v2 を作る**） |
| 実装 | `android/core/wire/` — 33テスト（往復・前方互換・不正入力） |
| 切替 | `app/.../data/MessageWire.kt` の `YosegiRollout`。**全フラグ off** |
| 研究記録 | `docs/RESEARCH_WIRE_FORMAT.md`、`research/wire/` |

**結論**: JSON の 23.8%。独自圧縮アルゴリズムは3案作って全部負けたので捨てた。
勝ったのは `java.util.zip.Deflater.setDictionary`（Android 標準、追加依存ゼロ）。

**重要な制約（測定では出てこなかったもの）**:
**Yosegi を Firestore のメッセージ文書に使ってはいけない。**
`firestore.rules` の保証はフィールド単位で動いており、blob にすると
「管理者でも本文を読めない」という構造的保証が、クライアントの行儀への期待に格下げされる。
使えるのは **Direct / Mesh / 一括転送**だけ。

### UI

- 時刻・既読を吹き出しの外へ（LINE と同じ配置）。短文が引き伸ばされる問題を解消
- 取り消しの表示に相手の名前
- 相手のアイコンを吹き出しの横に（連続発言では最初の1つだけ、場所は常に確保）

---

## 未了（優先順）

| # | 項目 | 詰まっているもの |
|---|---|---|
| 1 | **通話の実装（C-1〜）** | TURN は Cloudflare で確定。APK +40MB は許容と判断済み |
| 2 | **音声通話・ビデオ通話** | 設計済み。**TURN が要るかと APK が何MB増えるかの実測待ち**（C-1/C-5）|
| 3 | **画像の本体アップロード（M-3）** | 設計済み・サムネイルまで実装済み。`server/media.php` から |
| 3b | 動画 | 段階2。`MEDIA_ARCHITECTURE.md` §6 |
| 4 | W-2 バッテリ実測 | **優先度低下。** 2.80 µs が電池に見える経路が無い。測るなら辞書と無線 |
| 5 | 事前配布辞書 8 KB の生成と同梱 | Yosegi を実際に使う経路が決まってから |
| 6 | Direct-1 の実測（discovery / connection / latency） | 端末2台 |
| 7 | Direct-2（経路選択と Cloud fallback、`⚡ Direct` 表示） | Direct-1 の数字 |
| 8 | We2 の Direct 確認 | 修正は入れたが未検証 |
| 9 | 通知の疎通確認 | サーバは稼働中 |
| 10 | Tailscale の Windows 側 | PC の UAC ダイアログ |
| 11 | 吹き出し配置の残り（もしまだ気になるなら） | 判断 |

---

## 触ってはいけないもの

- **XServer のブログのファイル。** Rinowa 用は `echo.nextlab.blog` 配下だけ
- **`docs/YOSEGI_V1_SPEC.md`。** 凍結済み。変えるなら v2
- **`firestore.rules` の構造。** ルールが本文を守っている実体なので、
  変えるときは `rules-tests/run.js`（29件）を必ず通す
- **`research/wire/results.txt` の `Yosegi+KANA8 / deflate+dict32k` 行。**
  辞書の符号化が揃っていない誤った数字。**消さずに残してある** —
  辞書のベンチマークがどう人を騙すかの実例として

---

## 環境

- Pixel 10（API 37）と arrows We2 F-52E（API 36）。**エミュレータなし**
- 端末は共有。`adb` を握る前に、いま誰も使っていないことを確かめる
- 鍵は `C:\dev\echo-keys\`。**チャットに出さない**
- サーバ操作は Xserver CLI。`--servername` は初期ドメイン `nextlab.xsrv.jp`

---

## 決まっている原則（変えない）

- 管理者がメッセージ本文を読める経路を作らない。Analytics にも Crashlytics にも本文を渡さない
- **独自暗号方式を設計しない**
- Web/PWA を作らない。ネイティブのみ
- 押しつけるコンテンツを作らない
- 「Firebase にあるから使う」で採用しない。無いと何ができないかを言えるものだけ
