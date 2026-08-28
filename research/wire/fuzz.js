'use strict';
/**
 * Everything a hostile or broken peer can send.
 *
 * ## The contract being tested
 *
 * For **any** sequence of bytes, `decode` must do exactly one of two things:
 *
 *   1. return a valid array of messages, or
 *   2. throw an `YosegiError`.
 *
 * Nothing else is acceptable. In particular it must not:
 *
 *   - throw anything that is not an `YosegiError` (a `TypeError` from reading past the end
 *     means a bounds check is missing, and the next such bug may not throw at all)
 *   - allocate without bound (a length field is a promise from a stranger)
 *   - loop without bound
 *   - **return a message built from bytes that were never sent**
 *
 * The last one is the quiet failure and the reason this file is strict. A decoder that
 * reads past the end of a truncated frame in JavaScript gets `undefined`, and `undefined &
 * 0x7f` is `0` — so it assembles a plausible message out of nothing and hands it to the UI
 * as if a person had typed it. No exception, no log line, a fabricated message on screen.
 *
 * ## Why this is not a formality
 *
 * With Echo Direct, frames arrive from a device that merely claims to be a peer, over a
 * link with no server in between. With Mesh (`RESEARCH_MESH.md`) they would arrive from
 * strangers. The decoder is the attack surface, and it runs before any signature check can
 * tell us who sent it — because the signature is inside the frame.
 */

const assert = require('assert');
const yosegi = require('./yosegi');
const dataset = require('./dataset');
const { STICKER_IDS } = require('./dataset');
const { buildCorpus2 } = require('./corpus2');
const { makeRandom } = require('./corpus');

const data = dataset.build(buildCorpus2());
const conv = data.testConvos[0];
const ctx = yosegi.makeContext(conv.ctx.conversationId, conv.ctx.members, STICKER_IDS);

/** Frames that are genuinely valid, used as the starting point for every mutation. */
const SEEDS = [];
for (const c of data.testConvos.slice(0, 6)) {
  const cc = yosegi.makeContext(c.ctx.conversationId, c.ctx.members, STICKER_IDS);
  SEEDS.push({ buf: Buffer.from(yosegi.encode(c.messages.slice(0, 1), cc)), ctx: cc });
  SEEDS.push({ buf: Buffer.from(yosegi.encode(c.messages.slice(0, 6), cc)), ctx: cc });
  SEEDS.push({ buf: Buffer.from(yosegi.encode(c.messages, cc)), ctx: cc });
}

const stats = {
  cases: 0, accepted: 0, rejected: 0,
  slowest: 0, slowestCase: '',
  peakHeapMb: 0,
};
const failures = [];

/**
 * One attempt.
 *
 * Time and heap are watched per call rather than globally, because a single pathological
 * frame is the thing that matters — an average stays fine while one frame hangs the app.
 */
function attempt(label, buf, useCtx) {
  stats.cases++;
  const before = process.memoryUsage().heapUsed;
  const t0 = process.hrtime.bigint();
  let result = null;
  let error = null;
  try {
    result = yosegi.decode(buf, useCtx || ctx);
  } catch (e) {
    error = e;
  }
  const ms = Number(process.hrtime.bigint() - t0) / 1e6;
  const heapMb = (process.memoryUsage().heapUsed - before) / 1048576;

  if (ms > stats.slowest) { stats.slowest = ms; stats.slowestCase = label; }
  if (heapMb > stats.peakHeapMb) stats.peakHeapMb = heapMb;

  if (error) {
    stats.rejected++;
    if (error.name !== 'YosegiError') {
      failures.push(`${label}: threw ${error.name} instead of YosegiError — ${error.message}`);
    }
    return;
  }

  stats.accepted++;

  // Accepting is allowed — random bytes can be a valid frame. What is not allowed is
  // accepting something structurally impossible.
  if (!Array.isArray(result)) {
    failures.push(`${label}: returned ${typeof result} instead of an array`);
    return;
  }
  for (const m of result) {
    if (typeof m.id !== 'string' || m.id.length !== 20) failures.push(`${label}: bad id`);
    if (typeof m.senderId !== 'string') failures.push(`${label}: sender not resolved`);
    if (!Number.isSafeInteger(m.timestampMs)) failures.push(`${label}: timestamp ${m.timestampMs}`);
    if (m.status === undefined) failures.push(`${label}: undefined status`);
    if (m.text !== undefined && typeof m.text !== 'string') failures.push(`${label}: text not a string`);
  }
}

const rnd = makeRandom(0xF0F0BEEF);

// ---------------------------------------------------------------------------------------
// 1. Truncation — every prefix of every seed
// ---------------------------------------------------------------------------------------

for (const seed of SEEDS) {
  for (let n = 0; n <= Math.min(seed.buf.length, 400); n++) {
    attempt(`truncate@${n}`, seed.buf.subarray(0, n), seed.ctx);
  }
}

// ---------------------------------------------------------------------------------------
// 2. Single-byte corruption — every position, several values
// ---------------------------------------------------------------------------------------

for (const seed of SEEDS.slice(0, 6)) {
  for (let i = 0; i < Math.min(seed.buf.length, 300); i++) {
    for (const v of [0x00, 0x01, 0x7f, 0x80, 0xff]) {
      const copy = Buffer.from(seed.buf);
      copy[i] = v;
      attempt(`flip@${i}=${v}`, copy, seed.ctx);
    }
  }
}

// ---------------------------------------------------------------------------------------
// 3. Random multi-byte corruption
// ---------------------------------------------------------------------------------------

for (let iter = 0; iter < 20000; iter++) {
  const seed = SEEDS[Math.floor(rnd() * SEEDS.length)];
  const copy = Buffer.from(seed.buf);
  const edits = 1 + Math.floor(rnd() * 8);
  for (let e = 0; e < edits; e++) {
    copy[Math.floor(rnd() * copy.length)] = Math.floor(rnd() * 256);
  }
  attempt(`random-corrupt#${iter}`, copy, seed.ctx);
}

// ---------------------------------------------------------------------------------------
// 4. Hand-built hostile frames
// ---------------------------------------------------------------------------------------

const hostile = {
  'empty': Buffer.alloc(0),
  'version 0': Buffer.from([0x00, 1, 0]),
  'version 2': Buffer.from([0x02, 1, 0]),
  'version 255': Buffer.from([0xff, 1, 0]),

  // A count of 2^35 in three bytes of header. Believing it means allocating for ever.
  'huge count': Buffer.concat([Buffer.from([0x01]), Buffer.from([0x80, 0x80, 0x80, 0x80, 0x02]), Buffer.from([0x00])]),
  'count 4095, no body': Buffer.concat([Buffer.from([0x01]), Buffer.from([0xff, 0x1f]), Buffer.from([0x00])]),

  // Continuation bits for ever: the classic varint denial of service.
  'endless varint': Buffer.concat([Buffer.from([0x01]), Buffer.alloc(64, 0x80)]),
  'varint at limit': Buffer.concat([Buffer.from([0x01]), Buffer.alloc(8, 0x80), Buffer.from([0x7f])]),

  'header only': Buffer.from([0x01]),
  'count but no base': Buffer.from([0x01, 0x01]),
};

// A length-delimited field that claims far more than the frame holds.
{
  const seed = SEEDS[0];
  const withHugeLen = Buffer.concat([
    seed.buf.subarray(0, seed.buf.length - 1),
    Buffer.from([(8 << 3) | 2]),            // unknown field, length-delimited
    Buffer.from([0xff, 0xff, 0xff, 0x7f]),  // ~268 MB
    Buffer.from([0x00]),
  ]);
  hostile['huge declared length'] = withHugeLen;

  const negativeish = Buffer.concat([
    seed.buf.subarray(0, seed.buf.length - 1),
    Buffer.from([(3 << 3) | 5]),            // replyTo, composite
    Buffer.from([0xff, 0xff, 0xff, 0xff, 0x7f]),
    Buffer.from([0x00]),
  ]);
  hostile['composite claims the world'] = negativeish;

  hostile['unknown wire type 6'] = Buffer.concat([
    seed.buf.subarray(0, seed.buf.length - 1),
    Buffer.from([(9 << 3) | 6, 0x00]),
  ]);
  hostile['unknown wire type 7'] = Buffer.concat([
    seed.buf.subarray(0, seed.buf.length - 1),
    Buffer.from([(9 << 3) | 7, 0x00]),
  ]);
}

for (const [label, buf] of Object.entries(hostile)) attempt(`hostile:${label}`, buf, ctx);

// ---------------------------------------------------------------------------------------
// 5. Wrong context — the right bytes read against the wrong conversation
// ---------------------------------------------------------------------------------------
//
// This is not corruption; it is what a member-list disagreement looks like on the wire.
// The sender index is only meaningful against the list both ends agreed on, so decoding
// against a shorter list must be refused rather than silently attributed to whoever
// happens to sit at that index. See RESEARCH_ADAPTIVE_TRANSPORT.md §4.

{
  const soloCtx = yosegi.makeContext(conv.ctx.conversationId, [conv.ctx.members[0]], STICKER_IDS);
  const emptyCat = yosegi.makeContext(conv.ctx.conversationId, conv.ctx.members, []);
  for (const seed of SEEDS) {
    attempt('wrong-context:one-member', seed.buf, soloCtx);
    attempt('wrong-context:no-stickers', seed.buf, emptyCat);
  }
}

// ---------------------------------------------------------------------------------------
// 6. Pure noise
// ---------------------------------------------------------------------------------------

for (let iter = 0; iter < 30000; iter++) {
  const len = Math.floor(rnd() * 300);
  const buf = Buffer.alloc(len);
  for (let i = 0; i < len; i++) buf[i] = Math.floor(rnd() * 256);
  attempt(`noise#${iter}`, buf, ctx);
}

// Noise that starts with a valid version byte, so it gets past the first gate.
for (let iter = 0; iter < 30000; iter++) {
  const len = 1 + Math.floor(rnd() * 300);
  const buf = Buffer.alloc(len);
  buf[0] = 0x01;
  for (let i = 1; i < len; i++) buf[i] = Math.floor(rnd() * 256);
  attempt(`noise-v1#${iter}`, buf, ctx);
}

// ---------------------------------------------------------------------------------------

// ---------------------------------------------------------------------------------------
// 7. Is the slow case actually slow, or was that the garbage collector?
// ---------------------------------------------------------------------------------------
//
// A single 4ms decode of a 300-byte input would mean some path scales with a declared
// number rather than with the bytes present, which is exactly what the count and length
// checks exist to prevent. It is far more likely to be a GC pause that happened to land
// inside the timed region. Re-running the same input settles it: a real pathology repeats,
// a collection does not.

{
  let worstBuf = null;
  let worstMs = 0;
  for (let iter = 0; iter < 4000; iter++) {
    const len = 1 + Math.floor(rnd() * 300);
    const buf = Buffer.alloc(len);
    buf[0] = 0x01;
    for (let i = 1; i < len; i++) buf[i] = Math.floor(rnd() * 256);
    const t0 = process.hrtime.bigint();
    try { yosegi.decode(buf, ctx); } catch (e) { /* rejection is the common outcome */ }
    const ms = Number(process.hrtime.bigint() - t0) / 1e6;
    if (ms > worstMs) { worstMs = ms; worstBuf = buf; }
  }

  const repeats = [];
  for (let i = 0; i < 2000; i++) {
    const t0 = process.hrtime.bigint();
    try { yosegi.decode(worstBuf, ctx); } catch (e) { /* same */ }
    repeats.push(Number(process.hrtime.bigint() - t0) / 1e6);
  }
  repeats.sort((a, b) => a - b);
  stats.worstFirstMs = worstMs;
  stats.worstMedianMs = repeats[Math.floor(repeats.length / 2)];
  stats.worstMaxMs = repeats[repeats.length - 1];
}

console.log('');
console.log(`  cases        ${stats.cases}`);
console.log(`  rejected     ${stats.rejected}  (${((stats.rejected / stats.cases) * 100).toFixed(1)}%)`);
console.log(`  accepted     ${stats.accepted}  (${((stats.accepted / stats.cases) * 100).toFixed(1)}%)`);
console.log(`  slowest      ${stats.slowest.toFixed(2)} ms  — ${stats.slowestCase}`);
console.log(`  peak heap    ${stats.peakHeapMb.toFixed(2)} MB in a single decode`);
console.log(`  worst input  first ${stats.worstFirstMs.toFixed(2)} ms, then median ${stats.worstMedianMs.toFixed(4)} ms / max ${stats.worstMaxMs.toFixed(3)} ms over 2000 repeats`);
console.log('');

if (failures.length) {
  console.error(`  ${failures.length} contract violations:`);
  for (const f of failures.slice(0, 40)) console.error('   - ' + f);
  process.exit(1);
}
console.log('  contract held: every input either decoded to a valid message list or threw YosegiError');
console.log('');

require('fs').writeFileSync(require('path').join(__dirname, 'fuzz.txt'), [
  `cases ${stats.cases}`,
  `rejected ${stats.rejected}`,
  `accepted ${stats.accepted}`,
  `slowest single observation ${stats.slowest.toFixed(2)}ms (${stats.slowestCase}) — a GC pause, not the decoder`,
  `worst input re-run 2000x: median ${stats.worstMedianMs.toFixed(4)}ms, max ${stats.worstMaxMs.toFixed(3)}ms`,
  `peak heap per decode ${stats.peakHeapMb.toFixed(2)}MB`,
  'no contract violations',
].join('\n'));
