'use strict';
/**
 * Round two: the honest follow-up questions.
 *
 * Round one said the trained model (ETX) loses to a plain substitution table (KANA8), and
 * that both lose to DEFLATE with a dictionary. Before discarding ETX, three questions have
 * to be answered, because discarding a thing for the wrong reason is as bad as keeping it:
 *
 *  Q1  Is ETX losing because a language model is the wrong idea here, or because *this*
 *      language model is weak? An order-1 model over codepoints is close to the least a
 *      model can do. Order-2 is one honest step up; if the gap does not close, the idea is
 *      wrong and not merely under-built.
 *
 *  Q2  Do KANA8 and a dictionary compose? They attack different redundancy — one the width
 *      of a character, the other the repetition of a phrase. If they compose, the best
 *      answer may be a combination rather than a winner.
 *
 *  Q3  How much of this is the corpus flattering dictionaries? A hand-written corpus
 *      repeats itself more than real chat does, and every dictionary method feeds on
 *      exactly that. The novel-text slice below is the pessimistic bound.
 */

const zlib = require('zlib');
const dataset = require('./dataset');
const { buildKana8, kana8Encode, kana8Decode } = require('./echotext');
const { Encoder, Decoder } = require('./rangecoder');

const Z = zlib.constants;
const data = dataset.build();

const trainLines = [];
for (const c of data.trainConvos) for (const m of c.messages) if (m.text) trainLines.push(m.text);
const testLines = [];
for (const c of data.testConvos) for (const m of c.messages) if (m.text) testLines.push(m.text);

const kana = buildKana8(trainLines.join('\n'));

// ---------------------------------------------------------------------------------------
// Q1 — ETX2: the same idea, one order deeper
// ---------------------------------------------------------------------------------------

/**
 * Order-2 with escape down to order-1, then order-0.
 *
 * Contexts are hashed into a fixed table, which is what any real implementation would do —
 * an exact order-2 table over a two-thousand symbol alphabet does not fit on a phone, let
 * alone in an APK. The hash collides; collisions cost ratio, not correctness.
 */
const O2_CONTEXTS = 4096;
const O1_CONTEXTS = 256;
const KEEP = 16;
const EOS = 0;
const ESC_RAW = 1;
const FIRST = 2;

function trainEtx2(lines) {
  const freq = new Map();
  for (const line of lines) for (const ch of line) {
    const cp = ch.codePointAt(0);
    freq.set(cp, (freq.get(cp) || 0) + 1);
  }
  const ranked = [...freq.entries()].sort((a, b) => b[1] - a[1]).map((e) => e[0]);
  const symbolOf = new Map();
  ranked.forEach((cp, i) => symbolOf.set(cp, i + FIRST));
  const n = ranked.length + FIRST;

  const o0 = new Uint32Array(n).fill(1);
  const o1 = Array.from({ length: O1_CONTEXTS }, () => new Map());
  const o2 = Array.from({ length: O2_CONTEXTS }, () => new Map());

  const h2 = (a, b) => ((a * 2654435761 + b * 40503) >>> 0) % O2_CONTEXTS;

  for (const line of lines) {
    let p1 = EOS; let p2 = EOS;
    const step = (s) => {
      o0[s]++;
      const c1 = o1[Math.min(p1, O1_CONTEXTS - 1)];
      c1.set(s, (c1.get(s) || 0) + 1);
      const c2 = o2[h2(p1, p2)];
      c2.set(s, (c2.get(s) || 0) + 1);
      p2 = p1; p1 = s;
    };
    for (const ch of line) step(symbolOf.get(ch.codePointAt(0)));
    step(EOS);
  }

  return {
    ranked, symbolOf, n, h2,
    o0: build(o0, 1 << 14),
    o1: o1.map((m) => table(m)),
    o2: o2.map((m) => table(m)),
    modelBytes: n * 2 + (O1_CONTEXTS + O2_CONTEXTS) * KEEP * 4,
  };
}

function table(m) {
  const top = [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, KEEP);
  const kept = top.reduce((a, [, x]) => a + x, 0);
  const all = [...m.values()].reduce((a, x) => a + x, 0);
  const w = new Uint32Array(top.length + 1);
  top.forEach(([, x], i) => { w[i] = x; });
  w[top.length] = Math.max(1, all - kept);
  const f = scale(w, 1 << 12);
  return { symbols: top.map(([s]) => s), index: new Map(top.map(([s], i) => [s, i])), freq: f, cum: cum(f) };
}

function build(counts, target) { const f = scale(counts, target); return { freq: f, cum: cum(f) }; }

function scale(counts, target) {
  const total = counts.reduce((a, b) => a + b, 0);
  const out = new Uint32Array(counts.length);
  let sum = 0;
  for (let i = 0; i < counts.length; i++) { out[i] = Math.max(1, Math.floor(counts[i] * target / total)); sum += out[i]; }
  if (sum < target) { let b = 0; for (let i = 1; i < out.length; i++) if (out[i] > out[b]) b = i; out[b] += target - sum; }
  return out;
}

function cum(f) { const c = new Uint32Array(f.length + 1); for (let i = 0; i < f.length; i++) c[i + 1] = c[i] + f[i]; return c; }
function find(c, v) { let lo = 0, hi = c.length - 1; while (lo + 1 < hi) { const m = (lo + hi) >> 1; if (c[m] <= v) lo = m; else hi = m; } return lo; }

function etx2Encode(text, M) {
  const e = new Encoder();
  let p1 = EOS; let p2 = EOS;
  const emit = (s) => {
    const t2 = M.o2[M.h2(p1, p2)];
    const at2 = t2.index.get(s);
    if (at2 !== undefined) {
      e.encode(t2.cum[at2], t2.freq[at2], t2.cum[t2.cum.length - 1]);
    } else {
      const esc2 = t2.freq.length - 1;
      e.encode(t2.cum[esc2], t2.freq[esc2], t2.cum[t2.cum.length - 1]);
      const t1 = M.o1[Math.min(p1, O1_CONTEXTS - 1)];
      const at1 = t1.index.get(s);
      if (at1 !== undefined) {
        e.encode(t1.cum[at1], t1.freq[at1], t1.cum[t1.cum.length - 1]);
      } else {
        const esc1 = t1.freq.length - 1;
        e.encode(t1.cum[esc1], t1.freq[esc1], t1.cum[t1.cum.length - 1]);
        e.encode(M.o0.cum[s], M.o0.freq[s], M.o0.cum[M.o0.cum.length - 1]);
      }
    }
    p2 = p1; p1 = s;
  };
  for (const ch of text) {
    const s = M.symbolOf.get(ch.codePointAt(0));
    if (s === undefined) {
      emit(ESC_RAW);
      let v = ch.codePointAt(0);
      while (v >= 0x80) { e.encode((v & 0x7f) | 0x80, 1, 256); v = Math.floor(v / 128); }
      e.encode(v, 1, 256);
    } else emit(s);
  }
  emit(EOS);
  return e.finish();
}

function etx2Decode(buf, M) {
  const d = new Decoder(buf);
  let p1 = EOS; let p2 = EOS; let out = '';
  const next = () => {
    const t2 = M.o2[M.h2(p1, p2)];
    let s;
    const a2 = find(t2.cum, d.probe(t2.cum[t2.cum.length - 1]));
    d.update(t2.cum[a2], t2.freq[a2]);
    if (a2 !== t2.freq.length - 1) s = t2.symbols[a2];
    else {
      const t1 = M.o1[Math.min(p1, O1_CONTEXTS - 1)];
      const a1 = find(t1.cum, d.probe(t1.cum[t1.cum.length - 1]));
      d.update(t1.cum[a1], t1.freq[a1]);
      if (a1 !== t1.freq.length - 1) s = t1.symbols[a1];
      else {
        s = find(M.o0.cum, d.probe(M.o0.cum[M.o0.cum.length - 1]));
        d.update(M.o0.cum[s], M.o0.freq[s]);
      }
    }
    p2 = p1; p1 = s;
    return s;
  };
  for (;;) {
    const s = next();
    if (s === EOS) break;
    if (s === ESC_RAW) {
      let v = 0, sh = 1;
      for (;;) { const b = d.probe(256); d.update(b, 1); v += (b & 0x7f) * sh; if ((b & 0x80) === 0) break; sh *= 128; }
      out += String.fromCodePoint(v);
      continue;
    }
    out += String.fromCodePoint(M.ranked[s - FIRST]);
  }
  return out;
}

const M2 = trainEtx2(trainLines);
for (const line of testLines.slice(0, 400).concat(['𠮷🍜', ''])) {
  const back = etx2Decode(etx2Encode(line, M2), M2);
  if (back !== line) throw new Error('ETX2 round-trip failed: ' + JSON.stringify(line) + ' -> ' + JSON.stringify(back));
}

// ---------------------------------------------------------------------------------------
// Q2 — do KANA8 and a dictionary compose?
// ---------------------------------------------------------------------------------------

const dictUtf8 = Buffer.from(trainLines.join('\n'), 'utf8').subarray(-32 * 1024);
const dictKana = Buffer.concat(trainLines.map((l) => kana8Encode(l, kana))).subarray(-32 * 1024);

// ---------------------------------------------------------------------------------------
// Q3 — the pessimistic slice: messages whose text never appears in training
// ---------------------------------------------------------------------------------------

const trainSet = new Set(trainLines);
const novel = testLines.filter((l) => !trainSet.has(l));

// ---------------------------------------------------------------------------------------

function score(lines, label) {
  const t = { utf8: 0, k8: 0, etx2: 0, zstd: 0, brotli: 0, dfd: 0, k8dfd: 0 };
  for (const line of lines) {
    const b = Buffer.from(line, 'utf8');
    const k = kana8Encode(line, kana);
    t.utf8 += b.length;
    t.k8 += k.length;
    t.etx2 += etx2Encode(line, M2).length;
    t.zstd += zlib.zstdCompressSync(b).length;
    t.brotli += zlib.brotliCompressSync(b, { params: { [Z.BROTLI_PARAM_QUALITY]: 11 } }).length;
    t.dfd += zlib.deflateRawSync(b, { level: 9, dictionary: dictUtf8 }).length;
    t.k8dfd += zlib.deflateRawSync(k, { level: 9, dictionary: dictKana }).length;
  }
  const n = lines.length;
  const out = [];
  out.push('');
  out.push(`=== ${label} — ${n} messages, ${(t.utf8 / n).toFixed(1)} B/msg as UTF-8 ===`);
  out.push('');
  out.push('  codec                    B/msg   vs UTF-8');
  out.push('  ' + '-'.repeat(40));
  const names = {
    k8: 'KANA8', etx2: 'ETX2 (order-2)', zstd: 'zstd L3', brotli: 'brotli q11',
    dfd: 'deflate+dict', k8dfd: 'KANA8 + deflate+dict',
  };
  for (const k of ['k8', 'etx2', 'zstd', 'brotli', 'dfd', 'k8dfd']) {
    out.push(`  ${names[k].padEnd(24)}${(t[k] / n).toFixed(1).padStart(6)}   ${((t[k] / t.utf8) * 100).toFixed(1).padStart(7)}%`);
  }
  return out;
}

const lines = [];
lines.push(`ETX2 model would ship at ${(M2.modelBytes / 1024).toFixed(1)}KB`);
lines.push(...score(testLines, 'Q1/Q2 — all test messages'));
lines.push(...score(novel, 'Q3 — only text that never appeared in training'));
lines.push('');

console.log(lines.join('\n'));
require('fs').writeFileSync(require('path').join(__dirname, 'round2.txt'), lines.join('\n'));
