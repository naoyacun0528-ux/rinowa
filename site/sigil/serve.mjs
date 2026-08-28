// 手元で見る。
//
//   node serve.mjs
//
import http from "http";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const TYPE = { ".html": "text/html; charset=utf-8", ".jpg": "image/jpeg", ".js": "text/javascript" };

http.createServer((req, res) => {
  const name = req.url.split("?")[0] === "/" ? "/index.html" : req.url.split("?")[0];
  const file = path.join(HERE, name);
  if (!file.startsWith(HERE) || !fs.existsSync(file)) { res.writeHead(404); res.end("nope"); return; }
  res.writeHead(200, { "content-type": TYPE[path.extname(file)] || "application/octet-stream" });
  res.end(fs.readFileSync(file));
}).listen(4319, () => console.log("http://localhost:4319"));
