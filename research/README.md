# research/

実験用。**アプリには一切リンクされていません。**

ここのコードは、判断の根拠を再現できる状態で残すためにあります。
結論だけがドキュメントにあって計測が消えていると、
半年後に「本当にそうだったのか」を確かめる手段が無くなるので。

**採用が決まった Yosegi v1 の本実装は `android/core/wire/` です。** ここは研究の記録。

## wire/ — データ形式と圧縮

```bash
cd research/wire
npm install
npm run verify   # 往復一致と前方互換。これが通らない限り数字は見ない
node fuzz.js     # 92,362 ケース。デコーダの契約が守られるか
node bench2.js   # W-3: 現実的コーパスでの再検証。結論はこれ
node dictsize.js # 辞書の大きさと、圧縮率・CPU の釣り合い
npm run bench    # 形式 × コーデック × ワークロードの全表。40分前後
node analyze.js  # JSON の内訳、メッセージ長別
node round2.js   # ETX2 と、未知の文だけでの再測定
node round3.js   # 符号化を揃えた辞書での再比較
```

出力:

| ファイル | 中身 |
|---|---|
| `w3.txt` | **W-3。現実的コーパスでの最終結果** |
| `fuzz.txt` | Fuzz の結果 |
| `dictsize.txt` | 辞書の大きさと圧縮率・CPU |
| `results.txt` / `results.json` | 全表（最初のコーパス） |
| `analysis.txt` | JSON のバイト内訳、メッセージ長別 |
| `round2.txt` | order-2 モデルと、訓練に無い文だけの比較 |
| `round3.txt` | `bench.js` の不公平を訂正した比較 |

結果の読み方は `docs/RESEARCH_WIRE_FORMAT.md`。仕様は `docs/YOSEGI_V1_SPEC.md`（凍結）。

> **`results.txt` の `Yosegi+KANA8 / deflate+dict32k` 行は信用しないでください。**
> `bench.js` は辞書を素の UTF-8 Yosegi から作り、それを KANA8 版にも使っています。
> 符号化が違えば辞書は当たらないので、KANA8 に不当に不利な数字です。
> 正しい比較は `round3.js` のほう。詳しくは `RESEARCH_WIRE_FORMAT.md` §1.6。
> 誤りを消さずに残してあるのは、**辞書のベンチマークがどう人を騙すかの実例**だからです。

### 何が入っているか

| ファイル | 役割 |
|---|---|
| `corpus.js` | 最初の合成コーパス。反復が多く、辞書に甘い |
| `corpus2.js` | **W-3 のコーパス。** テンプレート合成、URL・数字・絵文字・英語混在。テスト文の 68.3% が新規 |
| `dataset.js` | Rinowa の実際の封筒（Firestore の文書形状） |
| `yosegi.js` | Yosegi 参照実装。**Kotlin 版と挙動を一致させること** |
| `fuzz.js` | 切り詰め・破壊・巨大宣言長・無限 varint・不明版・ノイズ |
| `echotext.js` | KANA8（保留）と ETX（廃棄） |
| `rangecoder.js` | LZMA 方式のレンジコーダ。自作要素なし |

### 注意

- `bench.js` は**40分前後**かかります。brotli q11 が支配的です（それ自体が結果）
- 乱数は固定シード。同じ入力で同じ数字が出ます
- 測定はデスクトップ（Snapdragon X / V8）。
  **圧縮率はそのまま移りますが、時間は順序の参考のみ**
- **バッテリと実機性能は測っていません。** 実機は Direct Lab 画面の「計測」から
