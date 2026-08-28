'use strict';
/**
 * W-3: the same question, asked of a corpus that does not flatter anybody.
 *
 * `bench.js` ran a wide matrix over the first corpus, whose test split repeated the
 * training text often enough to make every dictionary look good. This runs the **narrow**
 * matrix — the seven candidates that are actually in contention — over both corpora, so
 * the difference between them is visible rather than argued about.
 *
 * Reported for each:
 *
 *  - bytes per message, and the same as a fraction of what Echo sends today (JSON)
 *  - **expansion**: how often the codec made the payload bigger. Split out by message
 *    length, because the average hides the case that is a third of real traffic
 *  - encode and decode time
 *
 * The recommendation stands or falls here. If `Yosegi + dictionary DEFLATE` loses its margin
 * once the text is genuinely novel, it does not get frozen.
 */

const zlib = require('zlib');
const { pack, unpack } = require('msgpackr');
const cborx = require('cbor-x');

const dataset = require('./dataset');
const yosegi = require('./yosegi');
const { STICKER_IDS } = require('./dataset');
const { buildCorpus } = require('./corpus');
const { buildCorpus2, corpusStats } = require('./corpus2');

const Z = zlib.constants;

const out = [];
const say = (s) => { out.push(s); console.log(s); };

// ---------------------------------------------------------------------------------------

function formatsFor() {
  return [
    { name: 'JSON', enc: (msgs) => Buffer.from(JSON.stringify(msgs), 'utf8'), dec: (b) => JSON.parse(b.toString('utf8')) },
    { name: 'MessagePack', enc: (msgs) => pack(msgs), dec: (b) => unpack(b) },
    { name: 'CBOR', enc: (msgs) => cborx.encode(msgs), dec: (b) => cborx.decode(b) },
    { name: 'Yosegi', yosegi: true },
  ];
}

function codecsFor(dict) {
  return [
    { name: 'none', enc: (b) => b, dec: (b) => b },
    { name: 'deflate', enc: (b) => zlib.deflateRawSync(b, { level: 9 }), dec: (b) => zlib.inflateRawSync(b) },
    {
      name: 'deflate+dict',
      enc: (b) => zlib.deflateRawSync(b, { level: 9, dictionary: dict }),
      dec: (b) => zlib.inflateRawSync(b, { dictionary: dict }),
    },
    { name: 'zstd L3', enc: (b) => zlib.zstdCompressSync(b), dec: (b) => zlib.zstdDecompressSync(b) },
    {
      name: 'zstd L19',
      enc: (b) => zlib.zstdCompressSync(b, { params: { [Z.ZSTD_c_compressionLevel]: 19 } }),
      dec: (b) => zlib.zstdDecompressSync(b),
    },
    {
      name: 'brotli q5',
      enc: (b) => zlib.brotliCompressSync(b, { params: { [Z.BROTLI_PARAM_QUALITY]: 5 } }),
      dec: (b) => zlib.brotliDecompressSync(b),
    },
    {
      name: 'brotli q11',
      enc: (b) => zlib.brotliCompressSync(b, { params: { [Z.BROTLI_PARAM_QUALITY]: 11 } }),
      dec: (b) => zlib.brotliDecompressSync(b),
    },
  ];
}

/** Only the combinations worth reporting; the full cross-product was already run once. */
const PAIRS = [
  ['JSON', 'none'], ['JSON', 'deflate'], ['JSON', 'deflate+dict'], ['JSON', 'zstd L3'], ['JSON', 'brotli q11'],
  ['MessagePack', 'none'], ['MessagePack', 'deflate+dict'],
  ['CBOR', 'none'], ['CBOR', 'deflate+dict'],
  ['Yosegi', 'none'],
  ['Yosegi', 'deflate'], ['Yosegi', 'deflate+dict'],
  ['Yosegi', 'zstd L3'], ['Yosegi', 'zstd L19'],
  ['Yosegi', 'brotli q5'], ['Yosegi', 'brotli q11'],
];

function encodeFrame(format, msgs, conv) {
  if (!format.yosegi) return format.enc(msgs);
  return Buffer.from(yosegi.encode(msgs, ctxFor(conv)));
}

function decodeFrame(format, buf, conv) {
  if (!format.yosegi) return format.dec(buf);
  return yosegi.decode(buf, ctxFor(conv));
}

const ctxCache = new Map();
function ctxFor(conv) {
  let c = ctxCache.get(conv);
  if (!c) {
    c = yosegi.makeContext(conv.ctx.conversationId, conv.ctx.members, STICKER_IDS);
    ctxCache.set(conv, c);
  }
  return c;
}

function timeIt(fn, totalBytes) {
  const reps = Math.max(1, Math.min(200, Math.floor((2 * 1024 * 1024) / Math.max(1, totalBytes))));
  const t0 = process.hrtime.bigint();
  for (let i = 0; i < reps; i++) fn();
  return { ms: Number(process.hrtime.bigint() - t0) / 1e6, reps };
}

// ---------------------------------------------------------------------------------------

function runCorpus(label, corpus) {
  ctxCache.clear();
  const data = dataset.build(corpus);
  const stats = corpusStats(corpus);

  // Dictionary from the training split only, in the encoding it will be used on.
  const dictParts = [];
  for (const c of data.trainConvos) {
    dictParts.push(Buffer.from(yosegi.encode(c.messages, yosegi.makeContext(c.ctx.conversationId, c.ctx.members, STICKER_IDS))));
  }
  const dictAll = Buffer.concat(dictParts);
  const DICT = dictAll.subarray(Math.max(0, dictAll.length - 32 * 1024));

  const formats = formatsFor();
  const codecs = codecsFor(DICT);
  const byName = (arr, n) => arr.find((x) => x.name === n);

  const items = { single: [], burst: [], sync: [] };
  for (const c of data.testConvos) {
    for (const m of c.messages) items.single.push({ msgs: [m], conv: c });
    for (let i = 0; i < c.messages.length; i += 6) items.burst.push({ msgs: c.messages.slice(i, i + 6), conv: c });
    items.sync.push({ msgs: c.messages, conv: c });
  }

  say('');
  say('='.repeat(78));
  say(`  ${label}`);
  say('='.repeat(78));
  say('');
  say(`  test messages   ${stats.testLines}   (${stats.uniqueTest} distinct)`);
  say(`  novel text      ${(stats.novelFraction * 100).toFixed(1)}%  — never appears in the training split`);
  say(`  median length   ${stats.medianChars} chars   p90 ${stats.p90Chars}   mean ${stats.meanBytes.toFixed(1)} B UTF-8`);
  say(`  dictionary      ${(DICT.length / 1024).toFixed(0)} KB, built from the training split only`);

  const results = {};

  for (const [wl, list] of Object.entries(items)) {
    say('');
    say(`### ${wl}  (n=${list.length})`);
    say('');
    say('  format       codec           B/msg   vs JSON   worse    enc us   dec us');
    say('  ' + '-'.repeat(68));

    const jsonBase = list.reduce((a, it) => a + Buffer.byteLength(JSON.stringify(it.msgs), 'utf8'), 0) / list.length;
    const rows = [];

    for (const [fName, cName] of PAIRS) {
      const format = byName(formats, fName);
      const codec = byName(codecs, cName);

      const frames = list.map((it) => encodeFrame(format, it.msgs, it.conv));
      const packed = frames.map((f) => Buffer.from(codec.enc(f)));

      // Every single one must come back byte-identical before it is scored.
      for (let i = 0; i < frames.length; i++) {
        if (!Buffer.from(codec.dec(packed[i])).equals(frames[i])) {
          throw new Error(`${fName}/${cName}: round-trip mismatch at ${i}`);
        }
      }

      const rawBytes = frames.reduce((a, f) => a + f.length, 0);
      const outBytes = packed.reduce((a, f) => a + f.length, 0);
      const worse = packed.filter((p, i) => p.length > frames[i].length).length;

      const e = timeIt(() => { for (const it of list) codec.enc(encodeFrame(format, it.msgs, it.conv)); }, rawBytes);
      const d = timeIt(() => { for (let i = 0; i < packed.length; i++) decodeFrame(format, Buffer.from(codec.dec(packed[i])), list[i].conv); }, outBytes);

      const row = {
        format: fName, codec: cName,
        perMsg: outBytes / list.length,
        vsJson: outBytes / list.length / jsonBase,
        worsePct: worse / list.length,
        encUs: (e.ms * 1000) / (e.reps * list.length),
        decUs: (d.ms * 1000) / (d.reps * list.length),
      };
      rows.push(row);
    }

    rows.sort((a, b) => a.perMsg - b.perMsg);
    for (const r of rows) {
      say(`  ${r.format.padEnd(13)}${r.codec.padEnd(14)}${r.perMsg.toFixed(1).padStart(7)}${(r.vsJson * 100).toFixed(1).padStart(9)}%${(r.worsePct * 100).toFixed(0).padStart(7)}%${r.encUs.toFixed(1).padStart(10)}${r.decUs.toFixed(1).padStart(9)}`);
    }
    results[wl] = rows;
  }

  // -------------------------------------------------------------------------------------
  // Expansion by length — where the averages hide the truth
  // -------------------------------------------------------------------------------------

  say('');
  say('### expansion by message length (single sends)');
  say('');
  say('  A codec that helps on average can still make a third of real messages bigger.');
  say('');

  const BUCKETS = [[0, 7], [7, 15], [15, 30], [30, 60], [60, 1e9]];
  const watch = [['Yosegi', 'none'], ['Yosegi', 'deflate+dict'], ['Yosegi', 'zstd L3'], ['Yosegi', 'brotli q11'], ['JSON', 'deflate+dict']];

  say('  chars      n  ' + watch.map(([f, c]) => `${f}/${c}`.padStart(17)).join(''));
  say('  ' + '-'.repeat(20 + watch.length * 17));

  for (const [lo, hi] of BUCKETS) {
    const sel = items.single.filter((it) => {
      const t = it.msgs[0].text;
      const n = t ? [...t].length : 0;
      return t && n >= lo && n < hi;
    });
    if (!sel.length) continue;
    const cells = watch.map(([fName, cName]) => {
      const format = byName(formats, fName);
      const codec = byName(codecs, cName);
      let bytes = 0; let worse = 0;
      for (const it of sel) {
        const f = encodeFrame(format, it.msgs, it.conv);
        const p = Buffer.from(codec.enc(f));
        bytes += p.length;
        if (p.length > f.length) worse++;
      }
      return `${(bytes / sel.length).toFixed(0)}B ${((worse / sel.length) * 100).toFixed(0)}%↑`.padStart(17);
    });
    const label2 = hi > 1e8 ? `${lo}+` : `${lo}-${hi}`;
    say(`  ${label2.padEnd(8)}${String(sel.length).padStart(5)}  ${cells.join('')}`);
  }

  return { stats, results };
}

// ---------------------------------------------------------------------------------------

const v1 = runCorpus('CORPUS 1 — the original, repetitive one (for comparison)', buildCorpus());
const v2 = runCorpus('CORPUS 2 — realistic mix: ja / en / emoji / URL / numbers / long', buildCorpus2());

say('');
say('='.repeat(78));
say('  W-3: what changed when the corpus got harder');
say('='.repeat(78));
say('');
say('  configuration          corpus 1    corpus 2     change');
say('  ' + '-'.repeat(58));
for (const [f, c] of [['Yosegi', 'none'], ['Yosegi', 'deflate+dict'], ['Yosegi', 'zstd L3'], ['JSON', 'deflate+dict'], ['MessagePack', 'none']]) {
  const a = v1.results.single.find((r) => r.format === f && r.codec === c);
  const b = v2.results.single.find((r) => r.format === f && r.codec === c);
  const delta = ((b.vsJson - a.vsJson) * 100);
  say(`  ${(f + '/' + c).padEnd(22)}${(a.vsJson * 100).toFixed(1).padStart(7)}%${(b.vsJson * 100).toFixed(1).padStart(11)}%${(delta >= 0 ? '+' : '') + delta.toFixed(1)}pt`.padEnd(60));
}
say('');

require('fs').writeFileSync(require('path').join(__dirname, 'w3.txt'), out.join('\n'));
