'use strict';
/**
 * Two home-grown candidates for the one thing general-purpose compressors are bad at:
 * a Japanese sentence too short for a match-finder to have anything to match.
 *
 * ## Why try at all, when zstd exists
 *
 * LZ77 and its descendants compress by pointing at something they have already seen. In a
 * twenty-byte payload there is nothing behind the cursor to point at, so the entire output
 * is literals plus framing, and the result is *larger* than the input. Measured: a 30-byte
 * Japanese sentence comes out of zstd at 39 bytes and out of brotli at 34.
 *
 * There are two ways past that, and they are worth separating because they cost wildly
 * different amounts:
 *
 *  - **KANA8** — stop spending three bytes on characters that are drawn from a set of two
 *    hundred. Pure substitution, no model, no arithmetic; a table lookup per character.
 *    This is the cheap answer.
 *  - **ETX** — model the language and arithmetic-code it. A static order-1 model over
 *    codepoints, trained offline, shipped as a table. This is the expensive answer, and it
 *    only earns its place if the ratio is far enough ahead to pay for the CPU and the
 *    table sitting in the APK.
 *
 * Both are measured against zstd and brotli with and without dictionaries. If a dictionary
 * on an existing codec matches them, neither ships — a format Echo has to maintain forever
 * needs to win by a margin, not a hair.
 *
 * ## Scope
 *
 * These encode text, not envelopes. Envelope size is Yosegi's job. Keeping them apart is what
 * makes it possible to say which of the two paid for a saving.
 */

const { Encoder, Decoder } = require('./rangecoder');

// ---------------------------------------------------------------------------------------
// KANA8 — one byte for the characters that actually turn up
// ---------------------------------------------------------------------------------------

/**
 * Builds the substitution table from training text.
 *
 * Slot budget, in a single byte:
 *   0x00–0x7F   ASCII, passed through unchanged
 *   0x80–0xFC   125 slots for the most frequent non-ASCII codepoints
 *   0xFD        the next 256 most frequent, in a following byte
 *   0xFE        varint index into the rest of the table
 *   0xFF        varint codepoint, for anything never seen in training
 *
 * Japanese casual chat is mostly hiragana, and there are 86 of those, so the 125 direct
 * slots cover the bulk of a typical sentence at one byte per character against UTF-8's
 * three. That is the whole idea; there is nothing cleverer going on.
 */
function buildKana8(trainText) {
  const freq = new Map();
  for (const ch of trainText) {
    const cp = ch.codePointAt(0);
    if (cp < 0x80) continue;
    freq.set(cp, (freq.get(cp) || 0) + 1);
  }
  const ranked = [...freq.entries()].sort((a, b) => b[1] - a[1]).map((e) => e[0]);
  const toSlot = new Map();
  ranked.forEach((cp, i) => toSlot.set(cp, i));
  return { ranked, toSlot, tableBytes: ranked.length * 3 };
}

function kana8Encode(text, model) {
  const out = [];
  for (const ch of text) {
    const cp = ch.codePointAt(0);
    if (cp < 0x80) { out.push(cp); continue; }
    const slot = model.toSlot.get(cp);
    if (slot === undefined) { out.push(0xFF); pushVarint(out, cp); continue; }
    if (slot < 125) { out.push(0x80 + slot); continue; }
    if (slot < 125 + 256) { out.push(0xFD, slot - 125); continue; }
    out.push(0xFE); pushVarint(out, slot - 125 - 256);
  }
  return Buffer.from(out);
}

function kana8Decode(buf, model) {
  let s = '';
  let i = 0;
  while (i < buf.length) {
    const b = buf[i++];
    if (b < 0x80) { s += String.fromCodePoint(b); continue; }
    if (b === 0xFF) { const [cp, n] = readVarint(buf, i); i = n; s += String.fromCodePoint(cp); continue; }
    if (b === 0xFD) { s += String.fromCodePoint(model.ranked[125 + buf[i++]]); continue; }
    if (b === 0xFE) { const [v, n] = readVarint(buf, i); i = n; s += String.fromCodePoint(model.ranked[125 + 256 + v]); continue; }
    s += String.fromCodePoint(model.ranked[b - 0x80]);
  }
  return s;
}

function pushVarint(out, v) {
  while (v >= 0x80) { out.push((v & 0x7f) | 0x80); v = Math.floor(v / 128); }
  out.push(v);
}

function readVarint(buf, i) {
  let result = 0; let shift = 1;
  for (;;) {
    const b = buf[i++];
    result += (b & 0x7f) * shift;
    if ((b & 0x80) === 0) return [result, i];
    shift *= 128;
  }
}

// ---------------------------------------------------------------------------------------
// ETX — a trained order-1 model with an arithmetic coder
// ---------------------------------------------------------------------------------------

const EOS = 0;      // end of message; also what makes the length implicit
const ESC_RAW = 1;  // a codepoint the training never saw; a varint follows, coded flat
const FIRST_SYMBOL = 2;

const CONTEXTS = 64;   // previous symbol, clamped. The frequent ones are the useful ones.
const PER_CONTEXT = 24; // successors kept per context, before falling back to order-0

/**
 * Trains the model.
 *
 * Only ever called with the training split. A model that has seen the test text would
 * report a ratio nobody could reproduce in production, which is the failure mode this
 * whole study exists to avoid.
 */
function trainEtx(trainLines) {
  const freq = new Map();
  for (const line of trainLines) {
    for (const ch of line) {
      const cp = ch.codePointAt(0);
      freq.set(cp, (freq.get(cp) || 0) + 1);
    }
  }
  const ranked = [...freq.entries()].sort((a, b) => b[1] - a[1]).map((e) => e[0]);
  const symbolOf = new Map();
  ranked.forEach((cp, i) => symbolOf.set(cp, i + FIRST_SYMBOL));
  const nSymbols = ranked.length + FIRST_SYMBOL;

  // Order 0, over symbol indices. Everything gets at least 1 so nothing is uncodable.
  const order0 = new Uint32Array(nSymbols).fill(1);
  // Order 1: counts[context][symbol], sparse.
  const counts = Array.from({ length: CONTEXTS }, () => new Map());

  for (const line of trainLines) {
    let prev = EOS;
    for (const ch of line) {
      const s = symbolOf.get(ch.codePointAt(0));
      order0[s]++;
      const c = Math.min(prev, CONTEXTS - 1);
      counts[c].set(s, (counts[c].get(s) || 0) + 1);
      prev = s;
    }
    order0[EOS]++;
    const c = Math.min(prev, CONTEXTS - 1);
    counts[c].set(EOS, (counts[c].get(EOS) || 0) + 1);
  }

  // Order-0 cumulative table, scaled so the total stays well inside the coder's precision.
  const order0Scaled = scale(order0, 1 << 14);
  const order0Cum = cumulative(order0Scaled);

  // Order-1: keep the top successors per context; everything else escapes to order-0.
  const ctxTables = counts.map((m) => {
    const top = [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, PER_CONTEXT);
    const kept = top.reduce((a, [, n]) => a + n, 0);
    const all = [...m.values()].reduce((a, n) => a + n, 0);
    const rest = Math.max(1, all - kept);

    const weights = new Uint32Array(top.length + 1);
    top.forEach(([, n], i) => { weights[i] = n; });
    weights[top.length] = rest; // the escape slot, always last
    const scaled = scale(weights, 1 << 12);
    return {
      symbols: top.map(([s]) => s),
      index: new Map(top.map(([s], i) => [s, i])),
      freq: scaled,
      cum: cumulative(scaled),
    };
  });

  const modelBytes = nSymbols * 2 + CONTEXTS * (PER_CONTEXT * 2 + PER_CONTEXT * 2 + 2);
  return { ranked, symbolOf, nSymbols, order0: order0Scaled, order0Cum, ctxTables, modelBytes };
}

/** Proportional rescale to a fixed total, with every symbol keeping at least one count. */
function scale(counts, target) {
  const total = counts.reduce((a, b) => a + b, 0);
  const out = new Uint32Array(counts.length);
  let sum = 0;
  for (let i = 0; i < counts.length; i++) {
    out[i] = Math.max(1, Math.floor(counts[i] * target / total));
    sum += out[i];
  }
  // The floor loses a few counts; give them to the most frequent symbol, which is where
  // they cost the least.
  if (sum < target) {
    let best = 0;
    for (let i = 1; i < out.length; i++) if (out[i] > out[best]) best = i;
    out[best] += target - sum;
  }
  return out;
}

function cumulative(freq) {
  const cum = new Uint32Array(freq.length + 1);
  for (let i = 0; i < freq.length; i++) cum[i + 1] = cum[i] + freq[i];
  return cum;
}

function findSymbol(cum, v) {
  let lo = 0; let hi = cum.length - 1;
  while (lo + 1 < hi) {
    const mid = (lo + hi) >> 1;
    if (cum[mid] <= v) lo = mid; else hi = mid;
  }
  return lo;
}

function etxEncode(text, model) {
  const enc = new Encoder();
  const total0 = model.order0Cum[model.order0Cum.length - 1];

  const emit = (s) => {
    const ctx = model.ctxTables[Math.min(prev, CONTEXTS - 1)];
    const totalC = ctx.cum[ctx.cum.length - 1];
    const at = ctx.index.get(s);
    if (at !== undefined) {
      enc.encode(ctx.cum[at], ctx.freq[at], totalC);
    } else {
      const escSlot = ctx.freq.length - 1;
      enc.encode(ctx.cum[escSlot], ctx.freq[escSlot], totalC);
      enc.encode(model.order0Cum[s], model.order0[s], total0);
    }
    prev = s;
  };

  let prev = EOS;
  for (const ch of text) {
    const cp = ch.codePointAt(0);
    const s = model.symbolOf.get(cp);
    if (s === undefined) {
      emit(ESC_RAW);
      // Flat, in bytes: a codepoint outside the training set carries no usable statistics,
      // and pretending otherwise would cost more than it saved.
      let v = cp;
      while (v >= 0x80) { enc.encode(((v & 0x7f) | 0x80), 1, 256); v = Math.floor(v / 128); }
      enc.encode(v, 1, 256);
    } else {
      emit(s);
    }
  }
  emit(EOS);
  return enc.finish();
}

function etxDecode(buf, model) {
  const dec = new Decoder(buf);
  const total0 = model.order0Cum[model.order0Cum.length - 1];
  let prev = EOS;
  let out = '';

  const next = () => {
    const ctx = model.ctxTables[Math.min(prev, CONTEXTS - 1)];
    const totalC = ctx.cum[ctx.cum.length - 1];
    const at = findSymbol(ctx.cum, dec.probe(totalC));
    dec.update(ctx.cum[at], ctx.freq[at]);
    let s;
    if (at === ctx.freq.length - 1) {
      s = findSymbol(model.order0Cum, dec.probe(total0));
      dec.update(model.order0Cum[s], model.order0[s]);
    } else {
      s = ctx.symbols[at];
    }
    prev = s;
    return s;
  };

  for (;;) {
    const s = next();
    if (s === EOS) break;
    if (s === ESC_RAW) {
      let v = 0; let shift = 1;
      for (;;) {
        const b = dec.probe(256);
        dec.update(b, 1);
        v += (b & 0x7f) * shift;
        if ((b & 0x80) === 0) break;
        shift *= 128;
      }
      out += String.fromCodePoint(v);
      continue;
    }
    out += String.fromCodePoint(model.ranked[s - FIRST_SYMBOL]);
  }
  return out;
}

module.exports = {
  buildKana8, kana8Encode, kana8Decode,
  trainEtx, etxEncode, etxDecode,
};
