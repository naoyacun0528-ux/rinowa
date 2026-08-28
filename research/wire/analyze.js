'use strict';
/**
 * The cuts the headline table cannot show.
 *
 *  1. Where JSON's bytes actually go, field by field. The Yosegi design rests on this; if the
 *     envelope were not the majority of a short message, the format would not be worth
 *     having and the claim should collapse here rather than in production.
 *  2. Size buckets. A mean over a corpus whose median message is twenty characters hides
 *     the case that matters most.
 *  3. Text alone, envelope removed — the only fair place to ask whether a trained model
 *     beats a general compressor at Japanese.
 */

const zlib = require('zlib');
const dataset = require('./dataset');
const yosegi = require('./yosegi');
const { STICKER_IDS } = require('./dataset');
const { buildKana8, kana8Encode, trainEtx, etxEncode } = require('./echotext');

const Z = zlib.constants;
const data = dataset.build();

const trainLines = [];
for (const c of data.trainConvos) for (const m of c.messages) if (m.text) trainLines.push(m.text);
const testLines = [];
for (const c of data.testConvos) for (const m of c.messages) if (m.text) testLines.push(m.text);

const kana = buildKana8(trainLines.join('\n'));
const etx = trainEtx(trainLines);

const out = [];
const say = (s) => { out.push(s); console.log(s); };

// ---------------------------------------------------------------------------------------
// 1. Where JSON's bytes go
// ---------------------------------------------------------------------------------------

say('');
say('=== 1. One message as JSON: where the bytes go ===');
say('');

const field = new Map();
let total = 0;
let count = 0;
for (const c of data.testConvos) {
  for (const m of c.messages) {
    count++;
    total += Buffer.byteLength(JSON.stringify([m]), 'utf8');
    for (const [k, v] of Object.entries(m)) {
      // key, quotes, colon, comma, then the value as JSON writes it
      const bytes = Buffer.byteLength(JSON.stringify(k) + ':' + JSON.stringify(v) + ',', 'utf8');
      field.set(k, (field.get(k) || 0) + bytes);
    }
  }
}
const rows = [...field.entries()].sort((a, b) => b[1] - a[1]);
say(`  ${count} messages, mean ${(total / count).toFixed(1)} bytes of JSON each`);
say('');
say('  field            B/msg    share  present');
say('  ' + '-'.repeat(46));
for (const [k, bytes] of rows) {
  const present = data.testConvos.reduce(
    (a, c) => a + c.messages.filter((m) => m[k] !== undefined).length, 0);
  say(`  ${k.padEnd(16)}${(bytes / count).toFixed(1).padStart(6)}   ${((bytes / total) * 100).toFixed(1).padStart(5)}%   ${((present / count) * 100).toFixed(0).padStart(3)}%`);
}

// The share that is text, versus the share that is not.
let textBytes = 0;
for (const c of data.testConvos) {
  for (const m of c.messages) if (m.text) textBytes += Buffer.byteLength(m.text, 'utf8');
}
say('');
say(`  the message itself: ${(textBytes / count).toFixed(1)} B/msg = ${((textBytes / total) * 100).toFixed(1)}% of the JSON`);
say(`  everything else   : ${((total - textBytes) / count).toFixed(1)} B/msg = ${(((total - textBytes) / total) * 100).toFixed(1)}%`);

// ---------------------------------------------------------------------------------------
// 2. Size buckets
// ---------------------------------------------------------------------------------------

say('');
say('=== 2. By message length — does the winner change with size? ===');
say('');

const BUCKETS = [[0, 10], [10, 20], [20, 40], [40, 80], [80, 1e9]];
const codecs = {
  'none': (b) => b,
  'zstd L3': (b) => zlib.zstdCompressSync(b),
  'brotli q11': (b) => zlib.brotliCompressSync(b, { params: { [Z.BROTLI_PARAM_QUALITY]: 11 } }),
};

say('  chars      n   JSON   Yosegi  Yosegi+K8 Yosegi+ETX  | Yosegi+zstd Yosegi+brotli');
say('  ' + '-'.repeat(66));
for (const [lo, hi] of BUCKETS) {
  let n = 0;
  const sum = { json: 0, yosegi: 0, k8: 0, etx: 0, zstd: 0, brotli: 0 };
  for (const c of data.testConvos) {
    const plain = yosegi.makeContext(c.ctx.conversationId, c.ctx.members, STICKER_IDS);
    const k8 = yosegi.makeContext(c.ctx.conversationId, c.ctx.members, STICKER_IDS,
      { encode: (s) => kana8Encode(s, kana) });
    const ex = yosegi.makeContext(c.ctx.conversationId, c.ctx.members, STICKER_IDS,
      { encode: (s) => etxEncode(s, etx) });
    for (const m of c.messages) {
      const len = m.text ? [...m.text].length : 0;
      if (!m.text || len < lo || len >= hi) continue;
      n++;
      sum.json += Buffer.byteLength(JSON.stringify([m]), 'utf8');
      const e = Buffer.from(yosegi.encode([m], plain));
      sum.yosegi += e.length;
      sum.k8 += yosegi.encode([m], k8).length;
      sum.etx += yosegi.encode([m], ex).length;
      sum.zstd += codecs['zstd L3'](e).length;
      sum.brotli += codecs['brotli q11'](e).length;
    }
  }
  if (!n) continue;
  const label = hi > 1e8 ? `${lo}+` : `${lo}-${hi}`;
  say(`  ${label.padEnd(8)}${String(n).padStart(5)}  ${(sum.json / n).toFixed(0).padStart(5)}${(sum.yosegi / n).toFixed(0).padStart(6)}${(sum.k8 / n).toFixed(0).padStart(7)}${(sum.etx / n).toFixed(0).padStart(8)}  |${(sum.zstd / n).toFixed(0).padStart(9)}${(sum.brotli / n).toFixed(0).padStart(11)}`);
}

// ---------------------------------------------------------------------------------------
// 3. Text alone
// ---------------------------------------------------------------------------------------

say('');
say('=== 3. The text on its own — is a trained model worth it? ===');
say('');

const dictText = Buffer.from(trainLines.join('\n'), 'utf8').subarray(-32 * 1024);
let u = 0; const t = { k8: 0, etx: 0, zstd: 0, brotli: 0, deflateDict: 0 };
for (const line of testLines) {
  const b = Buffer.from(line, 'utf8');
  u += b.length;
  t.k8 += kana8Encode(line, kana).length;
  t.etx += etxEncode(line, etx).length;
  t.zstd += zlib.zstdCompressSync(b).length;
  t.brotli += zlib.brotliCompressSync(b, { params: { [Z.BROTLI_PARAM_QUALITY]: 11 } }).length;
  t.deflateDict += zlib.deflateRawSync(b, { level: 9, dictionary: dictText }).length;
}
const n = testLines.length;
say(`  ${n} messages, ${(u / n).toFixed(1)} B/msg as UTF-8`);
say('');
say('  codec              B/msg   vs UTF-8   ships');
say('  ' + '-'.repeat(48));
const ship = {
  k8: `${(kana.tableBytes / 1024).toFixed(1)}KB table`,
  etx: `${(etx.modelBytes / 1024).toFixed(1)}KB model`,
  zstd: '-',
  brotli: '-',
  deflateDict: '32KB dict',
};
const label = {
  k8: 'KANA8', etx: 'ETX', zstd: 'zstd L3', brotli: 'brotli q11', deflateDict: 'deflate+dict',
};
for (const k of ['k8', 'etx', 'zstd', 'brotli', 'deflateDict']) {
  say(`  ${label[k].padEnd(18)}${(t[k] / n).toFixed(1).padStart(6)}   ${((t[k] / u) * 100).toFixed(1).padStart(7)}%   ${ship[k]}`);
}

// Bits per character, which is the language-independent way to read the same thing.
let chars = 0;
for (const line of testLines) chars += [...line].length;
say('');
say(`  ${chars} characters total`);
say(`  UTF-8       ${((u * 8) / chars).toFixed(2)} bits/char`);
say(`  KANA8       ${((t.k8 * 8) / chars).toFixed(2)} bits/char`);
say(`  ETX         ${((t.etx * 8) / chars).toFixed(2)} bits/char`);
say(`  zstd L3     ${((t.zstd * 8) / chars).toFixed(2)} bits/char`);

require('fs').writeFileSync(require('path').join(__dirname, 'analysis.txt'), out.join('\n'));
