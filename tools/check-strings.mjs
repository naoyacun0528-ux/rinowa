// 画面に出る言葉が、Android と iOS でずれていないか。
//
// **iOS に出てくる日本語は、必ず Android にも同じ形で存在すること。**
// これが破れるのは、写さずに書き直したときだけ。実際に一度そうなった:
// Android の「安全性の確認」が iOS では「この会話の指紋」になっていて、
// 意味は同じでも、二つの端末を並べた人には別のアプリに見える。
//
// 逆向き（Android にあって iOS に無い）は「まだ移していない」なので、
// 数えて出すだけで落とさない。移し終わるまでの残りが見えていればいい。
//
//   node tools/check-strings.mjs          出すだけ
//   node tools/check-strings.mjs --strict ずれていたら失敗する
import fs from "fs";
import path from "path";

const B = String.fromCharCode(92);
const LITERAL = new RegExp('"([^"' + B + B + ']*[ぁ-んァ-ヶ一-龠][^"' + B + B + ']*)"', "g");

function walk(dir, ext, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { if (e.name !== "build") walk(p, ext, out); }
    else if (e.name.endsWith(ext)) out.push(p);
  }
  return out;
}

/** コメントは落とす。**説明文の中の言い回しは画面に出ない。** */
function literals(files) {
  const found = new Map();
  for (const f of files) {
    let block = false;
    fs.readFileSync(f, "utf8").split(/\r?\n/).forEach((line, i) => {
      const t = line.trim();
      if (block) { if (t.includes("*/")) block = false; return; }
      if (t.startsWith("/*")) { if (!t.includes("*/")) block = true; return; }
      if (t.startsWith("//") || t.startsWith("*") || t.startsWith("///")) return;
      const m = line.match(LITERAL);
      if (m) for (const s of m) {
        const v = s.slice(1, -1);
        if (!found.has(v)) found.set(v, `${f}:${i + 1}`);
      }
    });
  }
  return found;
}

const android = literals(walk("android/app/src/main/java", ".kt"));
// 撮影用の足場は製品の画面ではないので見ない。
const ios = literals(walk("ios/App/Sources", ".swift").filter((p) => !p.includes("Debug")));

const drifted = [...ios].filter(([s]) => !android.has(s));
const notYet = [...android].filter(([s]) => !ios.has(s));

console.log(`Android ${android.size} 語 · iOS ${ios.size} 語`);
console.log("");
if (drifted.length) {
  console.log(`■ iOS で書き直されている言葉 — ${drifted.length} 件`);
  for (const [s, where] of drifted) console.log(`   「${s}」  ${where}`);
} else {
  console.log("■ iOS の言葉はすべて Android にある。");
}
console.log("");
console.log(`□ まだ移していない Android の言葉 — ${notYet.length} 件`);

const strict = process.argv.includes("--strict");
if (strict && drifted.length) {
  console.log("");
  console.log("**写さずに書き直した言葉がある。** Android の文字列をそのまま使うこと。");
  process.exit(1);
}
