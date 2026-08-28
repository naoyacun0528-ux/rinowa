'use strict';
/**
 * How big should the shipped dictionary be?
 *
 * It is paid for three times — bytes in the APK, milliseconds loading it into the
 * compressor on every message, and memory holding it resident — and bought once, in
 * compression ratio. `round3.js` answered this on the easy corpus; this asks it again on
 * the realistic one, because a dictionary's value is exactly the thing that changes when
 * the text stops repeating.
 */

const zlib = require('zlib');
const dataset = require('./dataset');
const yosegi = require('./yosegi');
const { STICKER_IDS } = require('./dataset');
const { buildCorpus2 } = require('./corpus2');

const data = dataset.build(buildCorpus2());

const parts = [];
for (const c of data.trainConvos) {
  parts.push(Buffer.from(yosegi.encode(c.messages, yosegi.makeContext(c.ctx.conversationId, c.ctx.members, STICKER_IDS))));
}
const full = Buffer.concat(parts);

const singles = [];
for (const c of data.testConvos) {
  const ctx = yosegi.makeContext(c.ctx.conversationId, c.ctx.members, STICKER_IDS);
  for (const m of c.messages) singles.push(Buffer.from(yosegi.encode([m], ctx)));
}
const rawMean = singles.reduce((a, f) => a + f.length, 0) / singles.length;

const out = [];
const say = (s) => { out.push(s); console.log(s); };

say('');
say(`  ${singles.length} single sends, ${rawMean.toFixed(1)} B/msg as raw Yosegi`);
say(`  training material available: ${(full.length / 1024).toFixed(0)} KB`);
say('');
say('  dict      B/msg   vs raw   worse   enc us   dec us');
say('  ' + '-'.repeat(52));

for (const size of [0, 1024, 2048, 4096, 8192, 16384, 32768]) {
  const dict = size ? full.subarray(Math.max(0, full.length - size)) : null;
  const enc = (b) => (dict ? zlib.deflateRawSync(b, { level: 9, dictionary: dict }) : b);
  const dec = (b) => (dict ? zlib.inflateRawSync(b, { dictionary: dict }) : b);

  let bytes = 0;
  let worse = 0;
  const packed = [];
  for (const f of singles) {
    const p = Buffer.from(enc(f));
    if (!Buffer.from(dec(p)).equals(f)) throw new Error('round-trip failed at dict ' + size);
    bytes += p.length;
    if (p.length > f.length) worse++;
    packed.push(p);
  }

  const REPS = 12;
  let t0 = process.hrtime.bigint();
  for (let r = 0; r < REPS; r++) for (const f of singles) enc(f);
  const encUs = Number(process.hrtime.bigint() - t0) / 1000 / (REPS * singles.length);

  t0 = process.hrtime.bigint();
  for (let r = 0; r < REPS; r++) for (const p of packed) dec(p);
  const decUs = Number(process.hrtime.bigint() - t0) / 1000 / (REPS * singles.length);

  const label = size ? `${size / 1024} KB` : 'none';
  say(`  ${label.padEnd(9)}${(bytes / singles.length).toFixed(1).padStart(6)}${((bytes / singles.length / rawMean) * 100).toFixed(1).padStart(9)}%${(((worse / singles.length) * 100).toFixed(0) + '%').padStart(8)}${encUs.toFixed(1).padStart(9)}${decUs.toFixed(1).padStart(9)}`);
}

say('');
require('fs').writeFileSync(require('path').join(__dirname, 'dictsize.txt'), out.join('\n'));
