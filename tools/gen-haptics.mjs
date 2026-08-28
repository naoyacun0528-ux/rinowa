import fs from "fs";
const j = JSON.parse(fs.readFileSync("C:/dev/echo/research/vectors/haptics.json", "utf8"));
// Float で書かれた値が Double に広がって 0.25999999... になっている。
// Swift 側も Float で持つので、元の見た目に丸め戻す。
const f = (n) => {
  const r = Math.fround(n);
  for (let d = 1; d <= 6; d++) {
    const c = Number(r.toFixed(d));
    if (Math.fround(c) === r) return c.toFixed(Math.max(2, d));
  }
  return String(r);
};
const order = ["Selection","Navigation","SoftConfirm","Send","Threshold","ThresholdRelease",
               "Reaction","ReadReceipt","Success","Warning","Error","Destructive"];
const lower = (s) => s[0].toLowerCase() + s.slice(1);

let out = `import Foundation

/// 触覚の調整表。
///
/// **このファイルは手で書かない。** research/vectors/haptics.json から起こしたもので、
/// 元は Android の \`HapticTokens.kt\`。数値がそのまま「感触」なので、写し間違えると
/// 「同じアプリなのに iPhone だと送信の手応えが弱い」という、誰も原因に辿り着けない
/// 壊れ方になる。作り直しは tools/gen-haptics.mjs。
///
/// 持っているのはエンベロープ段だけ。**両方の実装に共通して存在する唯一の段**だから。
/// Android のプリミティブ（TICK / CLICK / THUD）は Android の語彙で iOS に無く、
/// iOS の CoreHaptics は (強さ, 硬さ, 時間) で書くので、こちらとほぼそのまま対応する。
///
/// 鳴らすのは Apple の SDK が要るので、ここには入れない。ここは値だけ。

/// アプリの触覚の語彙。
///
/// 画面は「どう感じさせるか」を書かない。「何が起きたか」を書く。
/// 意味を波形に変えるのはこの層の仕事で、ここだけの仕事。
public enum HapticToken: String, CaseIterable {
`;
for (const t of order) out += `    case ${lower(t)} = "${t}"\n`;
out += `}

/// エンベロープの制御点1つ。
///
/// - intensity: 0..1 の振幅。利用者の強度設定で増減する。
/// - sharpness: 0..1 の硬さ。**利用者設定では増減させない** — 強さではなく
///   その触覚の性格を運ぶため。
/// - durationMs: 前の点からここへ移るまでの時間。
public struct HapticEnvelopePoint: Equatable {
    public let intensity: Float
    public let sharpness: Float
    public let durationMs: Int

    public init(_ intensity: Float, _ sharpness: Float, _ durationMs: Int) {
        self.intensity = intensity; self.sharpness = sharpness; self.durationMs = durationMs
    }
}

public struct HapticSpec: Equatable {
    public let initialSharpness: Float
    public let points: [HapticEnvelopePoint]
    /// この時間内には再発火しない。触覚を安っぽくする一番の要因＝連射を防ぐ。
    public let minIntervalMs: Int
    /// 触覚エンジンが無い端末での代替。
    public let fallback: HapticFallback
}

/// 細かい制御ができないときの、粗い代わり。
public enum HapticFallback: String, CaseIterable {
    case tick = "Tick", click = "Click", doubleClick = "DoubleClick", heavyClick = "HeavyClick"
}

public enum HapticTokens {
`;
for (const t of order) {
  const s = j.tokens[t];
  out += `\n    private static let ${lower(t)} = HapticSpec(\n`;
  out += `        initialSharpness: ${f(s.envelope.initialSharpness)},\n        points: [\n`;
  for (const p of s.envelope.points)
    out += `            HapticEnvelopePoint(${f(p.intensity)}, ${f(p.sharpness)}, ${p.durationMs}),\n`;
  out += `        ],\n        minIntervalMs: ${s.minIntervalMs},\n        fallback: .${lower(s.predefined)}\n    )\n`;
}
out += `
    private static let table: [HapticToken: HapticSpec] = [
`;
for (const t of order) out += `        .${lower(t)}: ${lower(t)},\n`;
out += `    ]

    public static subscript(token: HapticToken) -> HapticSpec { table[token]! }
}
`;
fs.writeFileSync("C:/dev/echo/ios/RinowaCore/Sources/RinowaCore/Haptics.swift", out);
console.log("Haptics.swift を起こした:", out.split("\n").length, "行");
