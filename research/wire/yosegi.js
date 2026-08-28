'use strict';
/**
 * Yosegi — Echo Wire Format.
 *
 * A binary encoding of the message envelope. Not a general serialisation format: it knows
 * what an Echo message is, and that knowledge is the whole point.
 *
 * ## Where the bytes actually go
 *
 * JSON spends almost all of its bytes on things that are not the message. Measured over
 * the test split (`node analyze.js`): a single send averages 240.3 bytes, of which the
 * text is 26.7. **The message itself is 11% of the message.**
 *
 *   senderId        42.0 B  17.5%  — a Firebase uid, written out as base62 text
 *   conversationId  40.0 B  16.6%  — already known to both ends
 *   text            36.0 B  15.0%  — the only field anyone wanted to send
 *   id              28.0 B  11.7%
 *   timestampMs     28.0 B  11.7%  — thirteen decimal digits
 *   senderName      24.9 B  10.3%  — already known to both ends
 *   status          17.7 B   7.4%
 *
 * So the four ideas below, in descending order of what they are worth:
 *
 *  1. **Say nothing both ends already know.** The conversation is the channel; its id and
 *     its member list do not need repeating per message. A sender becomes an index into
 *     the member list: one byte instead of forty-one. This is the single largest win in
 *     the format and it costs nothing but a handshake.
 *  2. **Ids are numbers wearing a costume.** A Firestore auto-id is twenty base62
 *     characters — 119 bits of entropy in 20 bytes of text. Base-converted, it is 15
 *     bytes, and no compressor can find that on its own because the text is random.
 *  3. **Timestamps are deltas.** Messages in a frame are minutes apart, so a delta from
 *     the frame's base is one to three varint bytes instead of thirteen digits.
 *  4. **Field names are numbers, and absent fields cost nothing.** A tag byte per present
 *     field; a message with no reply and no reactions never mentions them.
 *
 * ## What is deliberately not done
 *
 * The text is left as plain UTF-8 here. Compressing it is a separate decision measured
 * separately, and mixing the two would make it impossible to say which of the two paid.
 *
 * ## Relationship to the privacy rules
 *
 * This is a transport encoding, not a cipher, and it is not to be described as one
 * anywhere. It offers no confidentiality whatsoever: anyone holding the bytes can decode
 * them with this file. Encryption remains a separate, standard, unmodified layer — see
 * docs/PRIVACY_PRINCIPLES.md, which forbids designing our own.
 */

const ID_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
const ID_VALUE = new Int16Array(128).fill(-1);
for (let i = 0; i < ID_ALPHABET.length; i++) ID_VALUE[ID_ALPHABET.charCodeAt(i)] = i;

/** 56 bits: the largest value a double represents exactly, in seven-bit groups. */
const MAX_VARINT_BYTES = 8;

/** 62^20 < 2^120, so twenty characters always fit in fifteen bytes. */
const ID20_BYTES = 15;
/** 62^28 < 2^167, so twenty-eight fit in twenty-one. */
const ID28_BYTES = 21;

/**
 * Base62 text to packed bytes.
 *
 * Long multiplication over a byte array rather than BigInt: this runs once per id per
 * message on a phone, and the allocation BigInt does per operation would show up in the
 * timings for no benefit at this size.
 */
function packId(str, width) {
  const out = new Uint8Array(width);
  for (let i = 0; i < str.length; i++) {
    const v = ID_VALUE[str.charCodeAt(i)];
    if (v < 0) throw new Error('id character outside the Firestore alphabet');
    let carry = v;
    for (let j = width - 1; j >= 0; j--) {
      const t = out[j] * 62 + carry;
      out[j] = t & 0xff;
      carry = t >>> 8;
    }
    if (carry !== 0) throw new Error('id too large for width');
  }
  return out;
}

function unpackId(bytes, offset, width, length) {
  // **必ず複製する。** long division は work を破壊しながら進むので、参照を掴むと
  // 呼び出し元のフレームを書き潰す。Uint8Array の slice は複製を返すが、Node の
  // Buffer の slice は**参照**を返す。同じコードが、渡されたものによって
  // 壊したり壊さなかったりする。
  //
  // 実際にこれを踏んだ。ベクタを「書いて、読み返して確かめて、保存」した結果、
  // 確認そのものが id を全部ゼロにしていた。
  if (offset + width > bytes.length) {
    throw new YosegiError(`id needs ${width} bytes, got ${bytes.length - offset}`);
  }
  const work = new Uint8Array(width);
  for (let i = 0; i < width; i++) work[i] = bytes[offset + i];
  const chars = new Array(length);
  for (let i = length - 1; i >= 0; i--) {
    let rem = 0;
    for (let j = 0; j < width; j++) {
      const cur = rem * 256 + work[j];
      work[j] = Math.floor(cur / 62);
      rem = cur % 62;
    }
    chars[i] = ID_ALPHABET[rem];
  }
  return chars.join('');
}

/** Grows on demand; the caller never has to guess a size. */
class Writer {
  constructor() { this.buf = Buffer.alloc(256); this.len = 0; }
  _need(n) {
    if (this.len + n <= this.buf.length) return;
    let size = this.buf.length * 2;
    while (size < this.len + n) size *= 2;
    const next = Buffer.alloc(size);
    this.buf.copy(next, 0, 0, this.len);
    this.buf = next;
  }
  u8(v) { this._need(1); this.buf[this.len++] = v & 0xff; }
  varint(v) {
    this._need(10);
    while (v >= 0x80) { this.buf[this.len++] = (v & 0x7f) | 0x80; v = Math.floor(v / 128); }
    this.buf[this.len++] = v;
  }
  bytes(b) { this._need(b.length); Buffer.from(b).copy(this.buf, this.len); this.len += b.length; }
  str(s, codec) {
    const b = codec ? codec.encode(s) : Buffer.from(s, 'utf8');
    this.varint(b.length);
    this.bytes(b);
  }
  done() { return this.buf.subarray(0, this.len); }
}

/**
 * Every malformed frame ends here, and nothing else does.
 *
 * A distinct type so a caller can tell "this peer sent rubbish" apart from "this decoder
 * has a bug". Those need different responses: the first is dropped and logged as a peer
 * problem, the second must never happen and should be loud.
 */
class YosegiError extends Error {
  constructor(message) { super(message); this.name = 'YosegiError'; }
}

/**
 * A bounds-checked reader.
 *
 * Every method that advances checks first. The version before this one read past the end
 * and got `undefined`, which JavaScript quietly coerces — `undefined & 0x7f` is 0 — so a
 * truncated frame produced a *plausible looking message* built from bytes that were never
 * sent. Silently inventing content is far worse than refusing to decode.
 */
class Reader {
  constructor(buf) { this.buf = buf; this.pos = 0; }

  u8() {
    if (this.pos >= this.buf.length) throw new YosegiError('truncated: expected a byte');
    return this.buf[this.pos++];
  }

  /**
   * Capped at eight bytes — 56 bits, which is everything a double holds exactly.
   *
   * Eight rather than five because the frame's base timestamp is a millisecond epoch:
   * 1.7e12 needs six bytes on its own. Without a cap at all, a run of continuation bytes
   * is a loop bounded only by the attacker's patience.
   */
  varint() {
    let result = 0;
    let shift = 1;
    for (let i = 0; i < MAX_VARINT_BYTES; i++) {
      if (this.pos >= this.buf.length) throw new YosegiError('truncated: varint runs off the end');
      const b = this.buf[this.pos++];
      result += (b & 0x7f) * shift;
      if ((b & 0x80) === 0) {
        if (!Number.isSafeInteger(result)) throw new YosegiError('varint beyond exact integer range');
        return result;
      }
      shift *= 128;
    }
    throw new YosegiError(`varint longer than ${MAX_VARINT_BYTES} bytes`);
  }

  bytes(n) {
    if (n < 0) throw new YosegiError('negative length');
    if (this.pos + n > this.buf.length) {
      throw new YosegiError(`truncated: wanted ${n} bytes, ${this.remaining} left`);
    }
    const b = this.buf.subarray(this.pos, this.pos + n);
    this.pos += n;
    return b;
  }

  str(codec) {
    const b = this.bytes(this.varint());
    if (!codec) return b.toString('utf8');
    try {
      return codec.decode(b);
    } catch (e) {
      // A text codec is given attacker-controlled bytes. Whatever it does with them, the
      // failure has to arrive as a rejected frame and not as some other kind of error.
      throw new YosegiError('text codec rejected the payload: ' + e.message);
    }
  }

  get remaining() { return this.buf.length - this.pos; }
  get eof() { return this.pos >= this.buf.length; }
}

/**
 * Field tags: `(fieldNumber << 3) | wireType`.
 *
 * Presence is the flag; there are no null fields on the wire.
 *
 * The wire type is what makes an **unknown** field skippable, and that is the whole reason
 * it is here. Without it, the first version that adds a field — an edit timestamp, an
 * attachment, anything — makes every older client unable to parse the rest of the message.
 * Not "unable to show the new field": unable to parse *anything after it*, because it
 * cannot tell where the unknown field ends. In a messenger that failure mode is a message
 * that silently does not arrive, which is the worst bug this product can have.
 *
 * The cost is one length byte on the two composite fields, which appear in 10% and 14% of
 * messages: about 0.24 B on a 61 B frame, or 0.4%. Cheap for never having to say "update
 * the app or you will stop receiving messages".
 */
const WT_FLAG = 0;   // no payload at all
const WT_VARINT = 1;
const WT_BYTES = 2;  // varint length, then that many bytes
const WT_ID20 = 3;   // fixed 15
const WT_COMPOSITE = 5; // varint length, then sub-fields — skippable as a block

const T_END = 0;
const T_TEXT = (1 << 3) | WT_BYTES;
const T_STICKER_IDX = (2 << 3) | WT_VARINT;
const T_STICKER_STR = (2 << 3) | WT_BYTES;
const T_REPLY = (3 << 3) | WT_COMPOSITE;
const T_REACTIONS = (4 << 3) | WT_COMPOSITE;
const T_SENDER_NAME = (5 << 3) | WT_BYTES; // only when the member list does not give it
const T_RETRACTED = (6 << 3) | WT_FLAG;

/** Advances past a field this version does not know, using only its wire type. */
function skipUnknown(r, tag) {
  switch (tag & 7) {
    case WT_FLAG: return;
    case WT_VARINT: r.varint(); return;
    case WT_BYTES:
    case WT_COMPOSITE: r.bytes(r.varint()); return;
    case WT_ID20: r.bytes(ID20_BYTES); return;
    default:
      // An unknown wire type carries no length, so the rest of the frame is unreadable.
      // Failing loudly beats handing back a message assembled from misread bytes.
      //
      // YosegiError specifically, not a bare Error: this is reachable from a single flipped
      // bit in transit, so it is ordinary bad input and not a decoder bug. The fuzzer
      // found it thrown as a plain Error, which would have made a corrupted packet look
      // like an internal fault in the logs.
      throw new YosegiError('unknown wire type ' + (tag & 7));
  }
}

const STATUS = ['Sending', 'Sent', 'Delivered', 'Read', 'Failed'];

/** Matches ReactionPalette.emoji in Models.kt. Fixed, so an index is all the wire carries. */
const REACTION_PALETTE_SIZE = 6;

/**
 * The shared knowledge both ends have before a byte is sent.
 *
 * In Echo this is not an assumption: the conversation document carries `memberIds`, and
 * both Cloud and Direct deliver into a conversation that has already been opened. The
 * sticker catalogue is versioned and shipped with the app (STICKER_ARCHITECTURE.md).
 */
function makeContext(conversationId, memberIds, stickerCatalogue, textCodec) {
  const memberIndex = new Map();
  memberIds.forEach((m, i) => memberIndex.set(m, i));
  const stickerIndex = new Map();
  stickerCatalogue.forEach((s, i) => stickerIndex.set(s, i));
  return { conversationId, memberIds, memberIndex, stickerCatalogue, stickerIndex, textCodec };
}

/**
 * A frame: one or more messages for one conversation.
 *
 * Header is a version byte, a message count, and a base timestamp. Everything after is
 * relative to it.
 */
function encode(messages, ctx) {
  const w = new Writer();
  w.u8(0x01);
  w.varint(messages.length);

  const base = messages.length ? messages[0].timestampMs : 0;
  w.varint(base);

  let prev = base;
  for (const m of messages) {
    w.bytes(packId(m.id, ID20_BYTES));

    const memberIdx = ctx.memberIndex.get(m.senderId);
    if (memberIdx === undefined) {
      // Someone the context does not cover — a member added since the handshake. Falls
      // back to the full id rather than failing, because a format that can drop a message
      // is worse than a format that is occasionally fat.
      w.u8(0xff);
      w.bytes(packId(m.senderId, ID28_BYTES));
    } else {
      w.u8(memberIdx);
    }

    // Signed zig-zag: messages arrive out of order often enough that a negative delta must
    // not cost ten bytes.
    const delta = m.timestampMs - prev;
    w.varint(delta >= 0 ? delta * 2 : -delta * 2 - 1);
    prev = m.timestampMs;

    w.u8(STATUS.indexOf(m.status));

    if (m.retracted) w.u8(T_RETRACTED);
    if (m.text !== undefined) { w.u8(T_TEXT); w.str(m.text, ctx.textCodec); }
    if (m.stickerId !== undefined) {
      const s = ctx.stickerIndex.get(m.stickerId);
      if (s === undefined) { w.u8(T_STICKER_STR); w.str(m.stickerId); }
      else { w.u8(T_STICKER_IDX); w.varint(s); }
    }
    if (m.replyTo) {
      const sub = new Writer();
      sub.bytes(packId(m.replyTo.messageId, ID20_BYTES));
      sub.str(m.replyTo.senderName);
      sub.str(m.replyTo.excerpt, ctx.textCodec);
      w.u8(T_REPLY);
      const body = sub.done();
      w.varint(body.length);
      w.bytes(body);
    }
    if (m.reactions) {
      const sub = new Writer();
      const entries = Object.entries(m.reactions);
      sub.varint(entries.length);
      for (const [uid, palette] of entries) {
        const idx = ctx.memberIndex.get(uid);
        if (idx === undefined) { sub.u8(0xff); sub.bytes(packId(uid, ID28_BYTES)); }
        else sub.u8(idx);
        sub.u8(palette);
      }
      w.u8(T_REACTIONS);
      const body = sub.done();
      w.varint(body.length);
      w.bytes(body);
    }
    if (m.senderName !== undefined) { w.u8(T_SENDER_NAME); w.str(m.senderName); }

    w.u8(T_END);
  }
  return w.done();
}

/**
 * The smallest a message can possibly be: id, sender index, timestamp delta, status, END.
 *
 * This is what makes the declared count safe. A frame claiming a million messages in forty
 * bytes is rejected before a single object is allocated, rather than being discovered a
 * million iterations later — or not at all, if the loop allocates until the process dies.
 */
const MIN_MESSAGE_BYTES = ID20_BYTES + 1 + 1 + 1 + 1;

/** A ceiling on one frame, so a legitimate-looking header cannot ask for unbounded work. */
const MAX_MESSAGES_PER_FRAME = 4096;

function decode(buf, ctx) {
  if (!buf || buf.length === 0) throw new YosegiError('empty frame');
  const r = new Reader(buf);

  const version = r.u8();
  if (version !== 0x01) throw new YosegiError('unsupported Yosegi version ' + version);

  const count = r.varint();
  if (count > MAX_MESSAGES_PER_FRAME) {
    throw new YosegiError(`frame declares ${count} messages, limit is ${MAX_MESSAGES_PER_FRAME}`);
  }
  const base = r.varint();

  // The declared count is checked against the bytes actually present before anything is
  // allocated for it. Trusting a length field is how a decoder becomes a memory bomb.
  if (count * MIN_MESSAGE_BYTES > r.remaining) {
    throw new YosegiError(`frame declares ${count} messages but only ${r.remaining} bytes remain`);
  }

  const out = [];
  let prev = base;
  for (let i = 0; i < count; i++) {
    const m = { conversationId: ctx.conversationId };
    m.id = unpackId(r.bytes(ID20_BYTES), 0, ID20_BYTES, 20);

    const memberIdx = r.u8();
    if (memberIdx === 0xff) {
      m.senderId = unpackId(r.bytes(ID28_BYTES), 0, ID28_BYTES, 28);
    } else {
      if (memberIdx >= ctx.memberIds.length) {
        // Never fall through to `undefined`. A message whose sender cannot be resolved
        // would be attributed to nobody, or worse, silently to whoever is at that index
        // after the list changes. See the member-list hash in RESEARCH_ADAPTIVE_TRANSPORT.
        throw new YosegiError(`sender index ${memberIdx} outside a ${ctx.memberIds.length}-member conversation`);
      }
      m.senderId = ctx.memberIds[memberIdx];
    }

    const zig = r.varint();
    const delta = (zig % 2 === 0) ? zig / 2 : -(zig + 1) / 2;
    m.timestampMs = prev + delta;
    if (!Number.isSafeInteger(m.timestampMs)) throw new YosegiError('timestamp out of range');
    prev = m.timestampMs;

    const statusIdx = r.u8();
    if (statusIdx >= STATUS.length) throw new YosegiError('unknown status ' + statusIdx);
    m.status = STATUS[statusIdx];

    for (;;) {
      const tag = r.u8();
      if (tag === T_END) break;
      if (tag === T_RETRACTED) { m.retracted = true; continue; }
      if (tag === T_TEXT) { m.text = r.str(ctx.textCodec); continue; }
      if (tag === T_STICKER_IDX) {
        const s = r.varint();
        if (s >= ctx.stickerCatalogue.length) throw new YosegiError('sticker index ' + s + ' not in catalogue');
        m.stickerId = ctx.stickerCatalogue[s];
        continue;
      }
      if (tag === T_STICKER_STR) { m.stickerId = r.str(); continue; }
      if (tag === T_REPLY) {
        // Sub-readers are bounded by the block length, so a lie inside the block cannot
        // reach past it into the next message.
        const sub = new Reader(r.bytes(r.varint()));
        m.replyTo = {
          messageId: unpackId(sub.bytes(ID20_BYTES), 0, ID20_BYTES, 20),
          senderName: sub.str(),
          excerpt: sub.str(ctx.textCodec),
        };
        continue;
      }
      if (tag === T_REACTIONS) {
        const sub = new Reader(r.bytes(r.varint()));
        const n = sub.varint();
        // Two bytes minimum each; the block length is the ceiling on how many can exist.
        if (n * 2 > sub.remaining) throw new YosegiError('reaction count exceeds its block');
        m.reactions = {};
        for (let k = 0; k < n; k++) {
          const idx = sub.u8();
          let uid;
          if (idx === 0xff) {
            uid = unpackId(sub.bytes(ID28_BYTES), 0, ID28_BYTES, 28);
          } else {
            if (idx >= ctx.memberIds.length) throw new YosegiError('reaction member index out of range');
            uid = ctx.memberIds[idx];
          }
          const palette = sub.u8();
          if (palette >= REACTION_PALETTE_SIZE) throw new YosegiError('reaction palette index out of range');
          m.reactions[uid] = palette;
        }
        continue;
      }
      if (tag === T_SENDER_NAME) { m.senderName = r.str(); continue; }
      // A field from a newer version. Step over it and carry on: the message is still
      // deliverable without whatever this was, and refusing it would lose it entirely.
      skipUnknown(r, tag);
    }
    out.push(m);
  }
  return out;
}

module.exports = {
  encode, decode, makeContext, packId, unpackId, Writer, Reader, YosegiError,
  MAX_MESSAGES_PER_FRAME, REACTION_PALETTE_SIZE,
};
