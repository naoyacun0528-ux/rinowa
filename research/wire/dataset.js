'use strict';
/**
 * Echo's payloads, as objects.
 *
 * Mirrors `model/Models.kt` and the Firestore document shape the app writes today. The
 * point is to measure the thing that actually goes over the wire — an envelope with ids,
 * a timestamp, a status and sometimes a reply quote — rather than a bare string. In a
 * twenty-character message the envelope is the majority of the bytes, so a study that
 * compressed only the text would be measuring the wrong half.
 *
 * Four workloads, because they have genuinely different shapes and a codec that wins one
 * can lose another:
 *
 *  - `single`    one message, sent on its own. The common case, and the hard one.
 *  - `burst`     a handful of messages delivered together after a reconnect.
 *  - `sync`      a whole thread, as an initial load or a device switch.
 *  - `bundle`    a store-and-forward bundle: many messages for many recipients, the shape
 *                Echo Mesh would carry. See docs/RESEARCH_MESH.md.
 */

const { buildCorpus, makeRandom } = require('./corpus');

/** Firestore auto-ids: 20 characters from this alphabet. */
const ID_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';

function makeId(rnd) {
  let out = '';
  for (let i = 0; i < 20; i++) out += ID_ALPHABET[Math.floor(rnd() * ID_ALPHABET.length)];
  return out;
}

/** Firebase Auth uids are 28 characters. */
function makeUid(rnd) {
  let out = '';
  for (let i = 0; i < 28; i++) out += ID_ALPHABET[Math.floor(rnd() * ID_ALPHABET.length)];
  return out;
}

const NAMES = ['みなと', 'みゆき', 'そうた', 'ひなた', 'お母さん', 'お父さん', 'Kaito', 'ゆき'];
const STICKER_IDS = [
  'echo.core.smile', 'echo.core.cry', 'echo.core.thumbsup', 'echo.core.heart',
  'echo.core.sleep', 'echo.core.ok', 'echo.core.sorry', 'echo.core.thanks',
];

/**
 * One message as Echo stores and sends it.
 *
 * Field names are the ones in Firestore, not shortened for the benchmark's benefit —
 * shortening them by hand would hide exactly the cost the binary format is supposed to
 * remove, and make the comparison meaningless.
 */
function makeMessage(rnd, ctx, text) {
  const isSticker = rnd() < 0.08;
  const msg = {
    id: makeId(rnd),
    conversationId: ctx.conversationId,
    senderId: ctx.members[Math.floor(rnd() * ctx.members.length)],
    senderName: NAMES[Math.floor(rnd() * NAMES.length)],
    timestampMs: ctx.clock,
    status: ['Sent', 'Delivered', 'Read'][Math.floor(rnd() * 3)],
  };

  if (isSticker) msg.stickerId = STICKER_IDS[Math.floor(rnd() * STICKER_IDS.length)];
  else msg.text = text;

  // Roughly one message in nine quotes another, matching what the thread screens show.
  if (rnd() < 0.11) {
    msg.replyTo = {
      messageId: makeId(rnd),
      senderName: NAMES[Math.floor(rnd() * NAMES.length)],
      excerpt: text.slice(0, 24),
    };
  }

  // Reactions ride on the message document, so they are part of its size.
  if (rnd() < 0.15) {
    msg.reactions = {};
    const n = 1 + Math.floor(rnd() * 2);
    for (let i = 0; i < n; i++) {
      msg.reactions[ctx.members[Math.floor(rnd() * ctx.members.length)]] = Math.floor(rnd() * 6);
    }
  }

  // Between a few seconds and a few minutes apart, which is what makes delta encoding of
  // the timestamp worth anything.
  ctx.clock += 3000 + Math.floor(rnd() * 400000);
  return msg;
}

function makeConversation(rnd, thread) {
  const memberCount = rnd() < 0.6 ? 2 : 3 + Math.floor(rnd() * 3);
  const ctx = {
    conversationId: makeId(rnd),
    members: Array.from({ length: memberCount }, () => makeUid(rnd)),
    clock: Date.UTC(2026, 7, 1) + Math.floor(rnd() * 30 * 86400000),
  };
  return { ctx, messages: thread.map((line) => makeMessage(rnd, ctx, line)) };
}

/**
 * @param corpus optional `{train, test}` of threads. Defaults to the first, easier corpus;
 *   `corpus2.js` supplies the realistic one that W-3 asked for. Everything downstream is
 *   identical so the two can be compared line for line.
 */
function build(corpus) {
  const { train, test } = corpus || buildCorpus();
  const rnd = makeRandom(0xECC0);

  const trainConvos = train.map((t) => makeConversation(rnd, t));
  const testConvos = test.map((t) => makeConversation(rnd, t));

  /** Every message on its own — the payload of one send. */
  const single = [];
  for (const c of testConvos) for (const m of c.messages) single.push([m]);

  /** Small groups, as a reconnect delivers them. */
  const burst = [];
  for (const c of testConvos) {
    for (let i = 0; i < c.messages.length; i += 6) burst.push(c.messages.slice(i, i + 6));
  }

  /** A whole thread. */
  const sync = testConvos.map((c) => c.messages);

  /**
   * Bundles for store-and-forward: several conversations' traffic in one blob, which is
   * what a courier device would actually be holding.
   */
  const bundle = [];
  for (let i = 0; i < testConvos.length; i += 4) {
    bundle.push(testConvos.slice(i, i + 4).flatMap((c) => c.messages));
  }

  return {
    workloads: { single, burst, sync, bundle },
    /** Only ever used to train dictionaries and models. Never scored. */
    trainConvos,
    testConvos,
  };
}

module.exports = { build, makeId, makeUid, STICKER_IDS, NAMES };
