// core.html を包んで index.html を作る。
// 中身は core.html にしか無い。ここにあるのは head と OGP の指定だけ。
//
//   node build.mjs
//
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const core = fs.readFileSync(path.join(HERE, "core.html"), "utf8");

const URL = "https://echo.nextlab.blog/sigil/";
const IMG = "og-1.jpg";   // **差し替えたら名前も変える。** 同じ名前だと古い写しが出続ける
const DESC = "サーバーに残る本文は0バイト。鍵は7日で捨てる、Rinowa の封の仕組み。";
const ALT = "Sigil v1 — Rinowa のためだけに、ゼロから設計された。";

const page = `<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#07080C">
<meta name="color-scheme" content="dark">
<title>Sigil v1 — Rinowa</title>
<meta name="description" content="${DESC}">
<link rel="canonical" href="${URL}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="Rinowa">
<meta property="og:title" content="Sigil v1 — Rinowa">
<meta property="og:description" content="${DESC}">
<meta property="og:url" content="${URL}">
<meta property="og:locale" content="ja_JP">
<meta property="og:image" content="${URL}${IMG}">
<meta property="og:image:secure_url" content="${URL}${IMG}">
<meta property="og:image:type" content="image/jpeg">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:image:alt" content="${ALT}">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="Sigil v1 — Rinowa">
<meta name="twitter:description" content="${DESC}">
<meta name="twitter:image" content="${URL}${IMG}">
<meta name="twitter:image:alt" content="${ALT}">
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='14' fill='%2307080C'/%3E%3Ctext x='32' y='45' font-family='Helvetica,Arial' font-size='36' font-weight='700' fill='%23A7CCF3' text-anchor='middle'%3ER%3C/text%3E%3C/svg%3E">
</head>
<body>
${core}
</body>
</html>
`;

const out = path.join(HERE, "index.html");
fs.writeFileSync(out, page);
console.log("index.html  " + fs.statSync(out).size + " bytes");
