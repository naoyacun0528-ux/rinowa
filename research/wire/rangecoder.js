'use strict';
/**
 * A carryless range coder.
 *
 * The LZMA arrangement: a 32-bit range, a low accumulator wide enough to hold a pending
 * carry, and a cache byte with a run length so a carry can propagate back through a run of
 * 0xFF bytes without buffering the whole output.
 *
 * Nothing here is novel and nothing here should be. This is the well-trodden version
 * precisely so that the interesting question — whether a trained model beats zstd on
 * twenty-byte Japanese — is not confounded by a home-made entropy coder being subtly
 * wrong. It is exercised against a round-trip check over the entire corpus before any
 * number it produces is believed.
 *
 * JavaScript note: `low` reaches 2^40, which a double holds exactly, but `<<` in JS is a
 * 32-bit operation and would silently truncate it. Every shift of `low` is therefore
 * written as multiplication and a modulo.
 */

const TOP = 0x1000000;      // 2^24 — renormalise below this
const MASK32 = 0x100000000; // 2^32

class Encoder {
  constructor() {
    this.low = 0;
    this.range = 0xFFFFFFFF;
    this.cache = 0;
    this.cacheSize = 1;
    this.out = [];
  }

  _shiftLow() {
    const hi = Math.floor(this.low / MASK32);
    if (this.low < 0xFF000000 || hi === 1) {
      let temp = this.cache;
      do {
        this.out.push((temp + hi) & 0xff);
        temp = 0xFF;
      } while (--this.cacheSize);
      this.cache = Math.floor(this.low / TOP) & 0xff;
    }
    this.cacheSize++;
    this.low = (this.low % TOP) * 256;
  }

  /** @param cumFreq symbols below this one; @param freq its own weight; @param tot the total. */
  encode(cumFreq, freq, tot) {
    const r = Math.floor(this.range / tot);
    this.low += r * cumFreq;
    this.range = r * freq;
    while (this.range < TOP) {
      this.range = this.range * 256;
      this._shiftLow();
    }
  }

  finish() {
    for (let i = 0; i < 5; i++) this._shiftLow();
    // The first byte is always the initial empty cache; dropping it saves one byte on
    // every single message, which at this payload size is not nothing.
    return Buffer.from(this.out.slice(1));
  }
}

class Decoder {
  constructor(buf) {
    this.buf = buf;
    this.pos = 0;
    this.range = 0xFFFFFFFF;
    this.code = 0;
    for (let i = 0; i < 4; i++) this.code = this.code * 256 + (this.buf[this.pos++] | 0);
    this._r = 0;
  }

  /** The point on the number line, before the caller has worked out which symbol owns it. */
  probe(tot) {
    this._r = Math.floor(this.range / tot);
    const v = Math.floor(this.code / this._r);
    return v >= tot ? tot - 1 : v;
  }

  update(cumFreq, freq) {
    this.code -= this._r * cumFreq;
    this.range = this._r * freq;
    while (this.range < TOP) {
      this.code = this.code * 256 + (this.buf[this.pos++] | 0);
      this.range = this.range * 256;
    }
  }
}

module.exports = { Encoder, Decoder };
