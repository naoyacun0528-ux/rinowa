# 使っている道具とバージョン

2026-08-24 時点。すべて手元で実際に動いているものを確認して書いた。
依存ライブラリの正本は `android/gradle/libs.versions.toml`。ここが食い違ったら向こうが正しい。

## 開発機

| もの | バージョン |
| --- | --- |
| Windows 11 Home (ARM64) | 10.0.26200.9168 |
| Windows PowerShell | 5.1（`tools/release.ps1` はこれで動く前提） |
| Git | 2.55.0 |
| Node.js | 26.7.0 |
| npm | 11.19.0 |
| Firebase CLI | 15.27.0 |

## Android のビルド

| もの | バージョン |
| --- | --- |
| Android Gradle Plugin | 9.3.1 |
| Gradle | 9.7.0（wrapper） |
| Kotlin | 2.4.10 |
| JDK | Microsoft OpenJDK 25.0.4 (LTS) |
| Java 互換性 | ソース・ターゲットとも 21 |
| compileSdk / targetSdk | 37 |
| minSdk | 24 |
| Build Tools | 36.0.0、37.0.0 |
| Platforms | android-37.0、android-37.1 |
| Android SDK Command-line Tools | latest |
| adb | 1.0.41 (37.0.1) |

## Android の依存ライブラリ

| もの | バージョン | 何に使うか |
| --- | --- | --- |
| Compose BOM | 2026.08.00 | 画面全部 |
| androidx.core-ktx | 1.19.0 | |
| activity-compose | 1.13.0 | |
| lifecycle | 2.11.0 | |
| navigation-compose | 2.9.8 | |
| datastore-preferences | 1.2.1 | |
| kotlinx-collections-immutable | 0.5.1 | Compose に渡すリスト |
| kotlinx-coroutines | 1.11.0 | |
| Firebase BOM | 34.17.0 | Auth・Firestore・Messaging |
| google-services プラグイン | 4.5.0 | |
| Credential Manager | 1.6.0 | サインイン |
| googleid | 1.2.0 | Google サインイン |
| play-services-base | 18.9.0 | |
| play-services-auth | 21.4.0 | ドライブの認可のみ |
| play-services-nearby | 19.3.0 | Direct（検証中） |
| matrix-rustcomponents crypto-android | 26.1.4 | Olm / Megolm（E2EE 本体） |
| Tink | 1.18.0 | AES-GCM-HKDF-STREAMING（写真・動画の暗号化） |
| Media3 | 1.8.0 | Transformer で 720p 変換、ExoPlayer で再生 |
| WebRTC (io.github.webrtc-sdk) | 125.6422.07 | 通話 |
| Coil | 2.7.0 | |
| exifinterface | 1.4.1 | |
| JUnit | 4.13.2 | 単体テスト |
| org.json | 20250107 | 単体テスト用の実装（端末の org.json はテストでは常に例外を投げるため） |

## iOS 側（Windows 上で書いている共有コア）

| もの | バージョン |
| --- | --- |
| Swift | 6.3.3（aarch64-unknown-windows-msvc） |
| swift-tools-version | 5.9 |
| swift-crypto | 3.0.0 以上 |

## ルールのテスト

| もの | バージョン |
| --- | --- |
| @firebase/rules-unit-testing | ^4.0.1 |
| firebase (JS SDK) | ^11.0.0 |

Firestore エミュレータ上で `rules-tests/run.js` を走らせる。

## 実機（エミュレータは使わない）

| 端末 | Android | API |
| --- | --- | --- |
| Pixel 10 | 17 | 37 |
| arrows We2 (F-52E) | 16 | 36 |

## サーバー

Xserver 上の PHP（`server/` 以下）。`echo.nextlab.blog` に置いている。
写真・動画の保管庫、push の中継、ICE サーバー情報の配布。
