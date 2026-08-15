# Echo

ネイティブ・メッセンジャー。触覚と操作感を中心に設計する。

- パッケージ ID: `jp.echo.android`
- 現在のフェーズ: **Prototype 0**（ローカルのみ・ネットワークなし）
- 対象: Android（主開発）→ iOS（Android の操作感が確定してから）
- Web版は作らない

## ドキュメント

| ファイル | 内容 |
|---|---|
| [docs/PRODUCT_VISION.md](docs/PRODUCT_VISION.md) | 何を作り、何を作らないか。判断の優先順位 |
| [docs/PRIVACY_PRINCIPLES.md](docs/PRIVACY_PRINCIPLES.md) | **メッセージ本文の扱い。絶対禁止事項** |
| [docs/HAPTIC_DESIGN.md](docs/HAPTIC_DESIGN.md) | Haptic Design System の仕様 |
| [docs/STICKER_ARCHITECTURE.md](docs/STICKER_ARCHITECTURE.md) | スタンプ。端末内処理・ID 参照・ローカル保持 |
| [docs/SYNC_AND_BACKUP.md](docs/SYNC_AND_BACKUP.md) | アカウント同期と復元。SYNC / BACKUP / CACHE の区別 |
| [docs/ANALYTICS_SCHEMA.md](docs/ANALYTICS_SCHEMA.md) | 収集するイベント・型・privacy risk |
| [docs/SIGNING.md](docs/SIGNING.md) | **署名鍵。失うと更新を届けられなくなる** |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Prototype 0 / 1 / 2 / Public Beta |

## リポジトリ構成

```
.
├── docs/          仕様・思想（Android / iOS 共通）
├── android/       Android 実装（Kotlin + Jetpack Compose）
├── outputs/       ビルド成果物（バージョンごと）
├── tools/         リリース出力スクリプト
└── ios/           iOS 実装（SwiftUI）※ Prototype 0 完了後に着手
```

## 開発体制

コードは Claude Code が書く。開発者本人は実機で触り、操作感・触覚・挙動について指示を出す。

**「実装した」は完了ではない。** UI・アニメーション・触覚は、実機確認 → フィードバック → 調整のループを経てはじめて完了とする。
