# Lowan 0.18.6

0.18.5 で Lab を製品ビルドから外したとき、**検証する手段ごと外していました**。
そこを直しました。

## lab ビルドを足しました

`lowan-0.18.6-lab.apk`。**製品とまったく同じ作り**で、Lab の画面が入っています。

- release を丸ごと引き継ぐ（release 鍵で署名、R8、リソース削減）
- 触覚 Lab と Direct Lab、Direct の通信路、NetworkProbe、Rinowa の計測が入る
- applicationId は `blog.nextlab.echo.debug` なので、**製品のアプリの隣に並びます**
  （入れ替わりません）
- 配りません。`tools/publish.sh` が上げるのは release だけ

debug で測れば済む話ではないので、こうしました。触覚は debug でも同じように鳴りますが、
**Rinowa の計測は違います**。debug は R8 を通していないので、そこで出る時間は製品の
時間ではありません。製品の数字が欲しいなら製品と同じ作りで測る必要があります。

作り方:

```bash
powershell -File C:\dev\echo\tools\release.ps1 -Lab
```

既定では作りません。毎回2分と35MBを、測らない回にも払うことになるので。

## 3つのビルドの違い

| | release（製品） | lab（計測用） | debug（開発） |
|---|---|---|---|
| 署名 | release 鍵 | release 鍵 | debug 鍵 |
| R8 | あり | あり | なし |
| Lab の画面 | 無し | あり | あり |
| applicationId | `…echo` | `…echo.debug` | `…echo.debug` |
| 配る | する | しない | しない |

lab と debug は applicationId が同じなので同時には入りません。どちらも製品のアプリとは
別のアプリとして並びます。

## R8 の対応表で確認しました

| クラス | release | lab |
|---|---|---|
| HapticLabScreen | 0行 | 814行 |
| RinowaBenchmark | 0行 | 164行 |
| NearbyDirectTransport / NetworkProbe / DirectLabViewModel | 0行 | あり |

権限も release は 21、lab は 30（Direct の Bluetooth などが入るため）。

## まだ実機で確認していません

端末が2台とも外れています。次に繋いだときに、release で Lab の入口が消えていること、
lab を入れると製品のアプリの隣に並んで Lab が開けることを確認します。

0.18.5 の内容は `outputs/0.18.5/RELEASE-0.18.5.md` を参照。
