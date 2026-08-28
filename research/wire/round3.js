'use strict';
/**
 * Round three: a fairness bug in round one, and what it changes.
 *
 * `bench.js` builds its dictionary from plain UTF-8 Yosegi frames and then uses that same
 * dictionary for the KANA8 variant too. That is not a fair test of KANA8: a dictionary is
 * a pile of bytes a compressor looks for matches in, and KANA8 changes what the bytes are.
 * Handing it a dictionary written in a different encoding is like giving a Japanese
 * dictionary to someone reading Korean.
 *
 * So `bench.js` shows KANA8 *losing* once a dictionary is involved (47.9 B against Yosegi's
 * 43.0). This file asks the question properly: **each format gets a dictionary built in its
 * own encoding.** The answer decides whether KANA8 is adopted everywhere or only where
 * there is no dictionary.
 *
 * The other question here: what does the 32KB dictionary actually cost per message? The
 * bench timings say `deflate+dict32k` encodes at 175us against plain `deflate`'s 58us —
 * three times the work for a thirty-byte payload. If most of that is loading the
 * dictionary rather than compressing, the size win has a CPU price worth naming.
 */

const zlib = require('zlib');
const dataset = require('./dataset');
const yosegi = require('./yosegi');
const { STICKER_IDS } = require('./dataset');
const { buildKana8, kana8Encode, kana8Decode } = require('./echotext');

const data = dataset.build();
const trainLines = [];
for (const c of data.trainConvos) for (const m of c.messages) if (m.text) trainLines.push(m.text);
const kana = buildKana8(trainLines.join('\n'));
const kanaCodec = { encode: (s) => kana8Encode(s, kana), decode: (b) => kana8Decode(b, kana) };

/** A dictionary in the same encoding as the frames it will be used on. */
function dictionaryFor(textCodec, limit) {
  const parts = [];
  for (const c of data.trainConvos) {
    const ctx = yosegi.makeContext(c.ctx.conversationId, c.ctx.members, STICKER_IDS, textCodec);
    parts.push(Buffer.from(yosegi.encode(c.messages, ctx)));
  }
  const all = Buffer.concat(parts);
  return all.subarray(Math.max(0, all.length - limit));
}

const DICT_PLAIN = dictionaryFor(undefined, 32 * 1024);
const DICT_KANA = dictionaryFor(kanaCodec, 32 * 1024);

const out = [];
const say = (s) => { out.push(s); console.log(s); };

// ---------------------------------------------------------------------------------------
// 1. Matched dictionaries
// ---------------------------------------------------------------------------------------

const singles = [];
for (const c of data.testConvos) for (const m of c.messages) singles.push({ m, c });

function run(textCodec, dict) {
  let raw = 0; let comp = 0; let worse = 0;
  for (const { m, c } of singles) {
    const ctx = yosegi.makeContext(c.ctx.conversationId, c.ctx.members, STICKER_IDS, textCodec);
    const frame = Buffer.from(yosegi.encode([m], ctx));
    const z = dict
      ? zlib.deflateRawSync(frame, { level: 9, dictionary: dict })
      : frame;
    // Proof it comes back, every time — a dictionary mismatch is silent otherwise.
    if (dict) {
      const back = zlib.inflateRawSync(z, { dictionary: dict });
      if (!back.equals(frame)) throw new Error('dictionary round-trip failed');
    }
    raw += frame.length;
    comp += z.length;
    if (z.length > frame.length) worse++;
  }
  return { raw: raw / singles.length, out: comp / singles.length, worse: worse / singles.length };
}

say('');
say('=== 1. Each format with a dictionary in its own encoding ===');
say('');
say('  format         no dict   +matched dict   worse');
say('  ' + '-'.repeat(50));

const plain = run(undefined, DICT_PLAIN);
const kanaMatched = run(kanaCodec, DICT_KANA);
const kanaMismatched = run(kanaCodec, DICT_PLAIN);

say(`  Yosegi            ${plain.raw.toFixed(1).padStart(7)}   ${plain.out.toFixed(1).padStart(13)}   ${(plain.worse * 100).toFixed(0).padStart(4)}%`);
say(`  Yosegi+KANA8      ${kanaMatched.raw.toFixed(1).padStart(7)}   ${kanaMatched.out.toFixed(1).padStart(13)}   ${(kanaMatched.worse * 100).toFixed(0).padStart(4)}%`);
say('');
say(`  (bench.js's unfair case — KANA8 frames, UTF-8 dictionary: ${kanaMismatched.out.toFixed(1)} B)`);

// ---------------------------------------------------------------------------------------
// 2. What the dictionary costs in CPU
// ---------------------------------------------------------------------------------------

say('');
say('=== 2. What loading a 32KB dictionary costs, per message ===');
say('');

const sampleMsgs = singles.slice(0, 500);
const sampleCtx = sampleMsgs.map(({ c }) =>
  yosegi.makeContext(c.ctx.conversationId, c.ctx.members, STICKER_IDS));
const frames = sampleMsgs.map(({ m }, i) => Buffer.from(yosegi.encode([m], sampleCtx[i])));

function timed(label, fn, reps) {
  const t0 = process.hrtime.bigint();
  for (let r = 0; r < reps; r++) for (const f of frames) fn(f);
  const us = Number(process.hrtime.bigint() - t0) / 1000 / (reps * frames.length);
  say(`  ${label.padEnd(34)}${us.toFixed(1).padStart(8)} us/msg`);
  return us;
}

const REPS = 20;
timed('deflate, no dictionary', (f) => zlib.deflateRawSync(f, { level: 9 }), REPS);
const withDict = timed('deflate + 32KB dictionary', (f) => zlib.deflateRawSync(f, { level: 9, dictionary: DICT_PLAIN }), REPS);
const with8k = timed('deflate + 8KB dictionary', (f) => zlib.deflateRawSync(f, { level: 9, dictionary: DICT_PLAIN.subarray(-8192) }), REPS);
const with2k = timed('deflate + 2KB dictionary', (f) => zlib.deflateRawSync(f, { level: 9, dictionary: DICT_PLAIN.subarray(-2048) }), REPS);
// The baseline the compression is being added on top of: what Yosegi costs on its own.
{
  const t0 = process.hrtime.bigint();
  for (let r = 0; r < REPS; r++) {
    for (let i = 0; i < sampleMsgs.length; i++) yosegi.encode([sampleMsgs[i].m], sampleCtx[i]);
  }
  const us = Number(process.hrtime.bigint() - t0) / 1000 / (REPS * sampleMsgs.length);
  say(`  ${'Yosegi encode itself (no compression)'.padEnd(34)}${us.toFixed(1).padStart(8)} us/msg`);
}

// Does a smaller dictionary give most of the win for a fraction of the cost?
say('');
say('=== 3. Dictionary size against what it buys ===');
say('');
say('  dict size    B/msg   vs no dict   enc us');
say('  ' + '-'.repeat(46));
const noDict = run(undefined, null);
say(`  none       ${noDict.out.toFixed(1).padStart(7)}       100.0%        -`);
for (const [label, size, us] of [['2 KB', 2048, with2k], ['8 KB', 8192, with8k], ['32 KB', 32768, withDict]]) {
  const r = run(undefined, DICT_PLAIN.subarray(-size));
  say(`  ${label.padEnd(10)}${r.out.toFixed(1).padStart(7)}   ${((r.out / noDict.out) * 100).toFixed(1).padStart(9)}%   ${us.toFixed(1).padStart(6)}`);
}

say('');
require('fs').writeFileSync(require('path').join(__dirname, 'round3.txt'), out.join('\n'));
