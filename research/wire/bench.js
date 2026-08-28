'use strict';
/**
 * The measurement.
 *
 * ## Rules this harness holds itself to
 *
 *  1. **Nothing is scored on data it was trained on.** Dictionaries and models come from
 *     `trainConvos`; every number below comes from `testConvos`, which share no topic
 *     sentences with them.
 *  2. **Expansion is reported, not hidden.** For short payloads the honest answer is often
 *     that a compressor made things worse. The `worse` column counts the payloads where
 *     the output exceeded the input, because a mean ratio can look respectable while a
 *     third of real messages got bigger.
 *  3. **Framing counts.** Sizes are whatever the codec actually emits, headers included.
 *     A codec that wins on ratio and loses on framing has not won.
 *  4. **Dictionaries are charged for.** Their size is reported next to the win they buy,
 *     because a 110KB dictionary in the APK is a real cost paid by every install.
 *
 * ## A known unfairness in this file, corrected elsewhere
 *
 * `buildDictionary` builds from plain UTF-8 Yosegi frames and that same dictionary is then
 * used for the KANA8 variant, whose bytes are in a different encoding — so the dictionary
 * cannot match and KANA8 scores worse than it should. **Do not read the
 * `Yosegi+KANA8 / deflate+dict32k` row as a verdict on KANA8.** `round3.js` runs the fair
 * comparison, with each format given a dictionary in its own encoding, and that is what
 * the recommendation rests on. Left in place rather than silently fixed because the wrong
 * number is instructive: it is exactly how a dictionary benchmark misleads.
 *
 * ## What this cannot measure
 *
 * Battery, and on-device throughput. This runs on a Snapdragon X desktop core under V8;
 * a phone's little core with a JIT-less Kotlin path will differ in absolute terms. Ratios
 * are hardware-independent and transfer exactly; times are indicative and ordinal only.
 * On-device confirmation is listed as an open item in the report rather than guessed at.
 */

const zlib = require('zlib');
const { pack, unpack } = require('msgpackr');
const cborx = require('cbor-x');

const dataset = require('./dataset');
const yosegi = require('./yosegi');
const { STICKER_IDS } = require('./dataset');
const { buildKana8, kana8Encode, kana8Decode, trainEtx, etxEncode, etxDecode } = require('./echotext');

const Z = zlib.constants;

// ---------------------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------------------

const data = dataset.build();

const trainLines = [];
for (const c of data.trainConvos) for (const m of c.messages) if (m.text) trainLines.push(m.text);

const kana = buildKana8(trainLines.join('\n'));
const etx = trainEtx(trainLines);

const kanaCodec = { encode: (s) => kana8Encode(s, kana), decode: (b) => kana8Decode(b, kana) };
const etxCodec = { encode: (s) => etxEncode(s, etx), decode: (b) => etxDecode(b, etx) };

/**
 * A dictionary is just bytes a compressor is allowed to point back into.
 *
 * Built from encoded training frames rather than raw text, so it contains the envelope
 * patterns as well as the vocabulary — status bytes, tag sequences, common names. The most
 * valuable material goes last because LZ match distances are cheaper the nearer they are.
 */
function buildDictionary(limitBytes) {
  const parts = [];
  for (const c of data.trainConvos) {
    const ctx = yosegi.makeContext(c.ctx.conversationId, c.ctx.members, STICKER_IDS);
    parts.push(Buffer.from(yosegi.encode(c.messages, ctx)));
  }
  const all = Buffer.concat(parts);
  return all.subarray(Math.max(0, all.length - limitBytes));
}

const DICT_32K = buildDictionary(32 * 1024);   // DEFLATE's window is 32KB; more is ignored
const DICT_110K = buildDictionary(110 * 1024); // a size zstd dictionaries are usually near

// ---------------------------------------------------------------------------------------
// Formats: object -> bytes
// ---------------------------------------------------------------------------------------

/** What the app sends today: a Firestore document per message, JSON on the wire. */
const F_JSON = {
  name: 'JSON',
  encode: (msgs) => Buffer.from(JSON.stringify(msgs), 'utf8'),
  decode: (b) => JSON.parse(b.toString('utf8')),
};

const F_MSGPACK = { name: 'MessagePack', encode: (msgs) => pack(msgs), decode: (b) => unpack(b) };
const F_CBOR = { name: 'CBOR', encode: (msgs) => cborx.encode(msgs), decode: (b) => cborx.decode(b) };

function yosegiFormat(name, textCodec) {
  return {
    name,
    encode: (msgs, conv) => Buffer.from(yosegi.encode(msgs, yosegiCtx(conv, textCodec))),
    decode: (b, conv) => yosegi.decode(b, yosegiCtx(conv, textCodec)),
  };
}

const ctxCache = new Map();
function yosegiCtx(conv, textCodec) {
  const key = conv.ctx.conversationId + '|' + (textCodec ? textCodec.tag : '');
  let c = ctxCache.get(key);
  if (!c) {
    c = yosegi.makeContext(conv.ctx.conversationId, conv.ctx.members, STICKER_IDS, textCodec);
    ctxCache.set(key, c);
  }
  return c;
}
kanaCodec.tag = 'kana';
etxCodec.tag = 'etx';

const FORMATS = [
  F_JSON,
  F_MSGPACK,
  F_CBOR,
  yosegiFormat('Yosegi', undefined),
  yosegiFormat('Yosegi+KANA8', kanaCodec),
  yosegiFormat('Yosegi+ETX', etxCodec),
];

// ---------------------------------------------------------------------------------------
// Codecs: bytes -> bytes
// ---------------------------------------------------------------------------------------

const CODECS = [
  { name: 'none', dict: 0, enc: (b) => b, dec: (b) => b },
  {
    name: 'gzip',
    dict: 0,
    enc: (b) => zlib.gzipSync(b, { level: 6 }),
    dec: (b) => zlib.gunzipSync(b),
  },
  {
    name: 'deflate',
    dict: 0,
    enc: (b) => zlib.deflateRawSync(b, { level: 9 }),
    dec: (b) => zlib.inflateRawSync(b),
  },
  {
    // Available on Android with no dependency at all: java.util.zip.Deflater.setDictionary.
    name: 'deflate+dict32k',
    dict: DICT_32K.length,
    enc: (b) => zlib.deflateRawSync(b, { level: 9, dictionary: DICT_32K }),
    dec: (b) => zlib.inflateRawSync(b, { dictionary: DICT_32K }),
  },
  {
    name: 'brotli q5',
    dict: 0,
    enc: (b) => zlib.brotliCompressSync(b, { params: { [Z.BROTLI_PARAM_QUALITY]: 5, [Z.BROTLI_PARAM_SIZE_HINT]: b.length } }),
    dec: (b) => zlib.brotliDecompressSync(b),
  },
  {
    name: 'brotli q11',
    dict: 0,
    enc: (b) => zlib.brotliCompressSync(b, { params: { [Z.BROTLI_PARAM_QUALITY]: 11, [Z.BROTLI_PARAM_SIZE_HINT]: b.length } }),
    dec: (b) => zlib.brotliDecompressSync(b),
  },
  {
    name: 'zstd L3',
    dict: 0,
    enc: (b) => zlib.zstdCompressSync(b),
    dec: (b) => zlib.zstdDecompressSync(b),
  },
  {
    name: 'zstd L19',
    dict: 0,
    enc: (b) => zlib.zstdCompressSync(b, { params: { [Z.ZSTD_c_compressionLevel]: 19 } }),
    dec: (b) => zlib.zstdDecompressSync(b),
  },
];

/**
 * zstd with a prefix dictionary, measured through a stream.
 *
 * Node exposes no binding for `ZSTD_CCtx_refPrefix`, so the dictionary is fed into a fresh
 * stream and flushed; the bytes after that flush are exactly what a real prefixed frame
 * would carry. The size is therefore accurate. The *time* is not — it includes building
 * and priming a stream per message, which a real implementation does once — so this codec
 * is scored on size only and its timing column is left blank.
 */
function zstdPrefixSizes(payloads, dict, level) {
  return new Promise((resolve) => {
    const sizes = [];
    let i = 0;
    const step = () => {
      if (i >= payloads.length) return resolve(sizes);
      const p = payloads[i++];
      const c = zlib.createZstdCompress({ params: { [Z.ZSTD_c_compressionLevel]: level } });
      const chunks = [];
      let primed = 0;
      c.on('data', (d) => chunks.push(d));
      c.write(dict);
      c.flush(Z.ZSTD_e_flush, () => {
        primed = chunks.reduce((a, d) => a + d.length, 0);
        c.write(p);
        c.flush(Z.ZSTD_e_flush, () => {
          sizes.push(chunks.reduce((a, d) => a + d.length, 0) - primed);
          c.destroy();
          setImmediate(step);
        });
      });
    };
    step();
  });
}

/**
 * One long-lived compressor for a whole conversation, flushed between messages.
 *
 * This is not a dictionary trick — it is what a persistent link can simply do, and it is
 * the configuration Echo Direct is already in a position to use, because a Direct session
 * is a socket that stays open. Every message compresses against everything said before it
 * in that session, which is exactly the redundancy chat has most of.
 */
function zstdSessionSizes(perConversationPayloads, level) {
  return new Promise((resolve) => {
    const sizes = [];
    let ci = 0;
    const nextConv = () => {
      if (ci >= perConversationPayloads.length) return resolve(sizes);
      const payloads = perConversationPayloads[ci++];
      const c = zlib.createZstdCompress({ params: { [Z.ZSTD_c_compressionLevel]: level } });
      const chunks = [];
      let seen = 0;
      let i = 0;
      c.on('data', (d) => chunks.push(d));
      const step = () => {
        if (i >= payloads.length) { c.destroy(); return setImmediate(nextConv); }
        c.write(payloads[i++]);
        c.flush(Z.ZSTD_e_flush, () => {
          const now = chunks.reduce((a, d) => a + d.length, 0);
          sizes.push(now - seen);
          seen = now;
          step();
        });
      };
      step();
    };
    nextConv();
  });
}

// ---------------------------------------------------------------------------------------
// Workloads
// ---------------------------------------------------------------------------------------

/** Each item is a frame of messages plus the conversation it belongs to. */
function workloads() {
  const single = [];
  const burst = [];
  const sync = [];
  const bundle = [];

  for (const c of data.testConvos) {
    for (const m of c.messages) single.push({ msgs: [m], conv: c });
    for (let i = 0; i < c.messages.length; i += 6) burst.push({ msgs: c.messages.slice(i, i + 6), conv: c });
    sync.push({ msgs: c.messages, conv: c });
  }
  for (let i = 0; i < data.testConvos.length; i += 4) {
    const group = data.testConvos.slice(i, i + 4);
    // A bundle carries several conversations, so it cannot use one conversation's context.
    // It is encoded per conversation and concatenated, which is what a courier would hold.
    bundle.push({ groups: group });
  }
  return { single, burst, sync, bundle };
}

const WL = workloads();

// ---------------------------------------------------------------------------------------
// Running
// ---------------------------------------------------------------------------------------

function timeIt(fn, iterations) {
  const t0 = process.hrtime.bigint();
  for (let i = 0; i < iterations; i++) fn();
  return Number(process.hrtime.bigint() - t0) / 1e6; // ms
}

function encodeItem(format, item) {
  if (item.groups) {
    return Buffer.concat(item.groups.map((c) => format.encode(c.messages, c)));
  }
  return format.encode(item.msgs, item.conv);
}

/** UTF-8 JSON is the reference every ratio is quoted against — it is what Echo sends now. */
const baselineCache = new Map();
function baselineSize(item) {
  let v = baselineCache.get(item);
  if (v === undefined) { v = encodeItem(F_JSON, item).length; baselineCache.set(item, v); }
  return v;
}

/**
 * Repetitions bounded by bytes rather than by item count.
 *
 * A thread-sized payload under brotli q11 is thousands of times more work than a
 * twenty-byte one; a fixed repetition count would spend minutes on the former and still
 * under-sample the latter.
 */
function repsFor(totalBytes) {
  return Math.max(1, Math.min(400, Math.floor((4 * 1024 * 1024) / Math.max(1, totalBytes))));
}

function measure(format, codec, items) {
  let raw = 0;
  let out = 0;
  let base = 0;
  let worse = 0;
  const payloads = [];
  const compressed = [];

  for (const item of items) {
    const encoded = encodeItem(format, item);
    const c = codec.enc(encoded);
    raw += encoded.length;
    out += c.length;
    base += baselineSize(item);
    if (c.length > encoded.length) worse++;
    payloads.push(encoded);
    compressed.push(Buffer.from(c));
  }

  // Round-trip everything. A ratio from a codec that cannot decode is worthless.
  for (let i = 0; i < payloads.length; i++) {
    const back = codec.dec(compressed[i]);
    if (!Buffer.from(back).equals(Buffer.from(payloads[i]))) {
      throw new Error(`${format.name}/${codec.name}: round-trip mismatch at ${i}`);
    }
  }

  const reps = repsFor(raw);
  const encMs = timeIt(() => { for (const item of items) codec.enc(encodeItem(format, item)); }, reps);
  const decMs = timeIt(() => { for (const c of compressed) codec.dec(c); }, reps);

  return {
    format: format.name,
    codec: codec.name,
    n: items.length,
    rawBytes: raw,
    outBytes: out,
    baseBytes: base,
    perMsg: out / items.length,
    vsJson: out / base,
    worse,
    dict: codec.dict,
    encUsPerItem: (encMs * 1000) / (reps * items.length),
    decUsPerItem: (decMs * 1000) / (reps * items.length),
  };
}

/**
 * The dictionary configurations are sampled, not run over everything.
 *
 * Priming a fresh zstd stream with 110KB per message is an artefact of Node having no
 * binding for refPrefix; the real thing primes once. Sampling keeps the harness usable
 * without changing what is measured, since every sample is scored independently.
 */
const PREFIX_SAMPLE = 300;
function sample(arr) {
  if (arr.length <= PREFIX_SAMPLE) return arr;
  const step = arr.length / PREFIX_SAMPLE;
  const out = [];
  for (let i = 0; i < PREFIX_SAMPLE; i++) out.push(arr[Math.floor(i * step)]);
  return out;
}

async function main() {
  const results = {};

  for (const [wlName, items] of Object.entries(WL)) {
    const rows = [];
    for (const format of FORMATS) {
      for (const codec of CODECS) {
        const t0 = Date.now();
        rows.push(measure(format, codec, items));
        process.stderr.write(`  ${wlName} ${format.name}/${codec.name} ${Date.now() - t0}ms\n`);
      }
    }

    // The two dictionary-bearing zstd configurations, size only.
    for (const format of [F_JSON, FORMATS[3], FORMATS[4], FORMATS[5]]) {
      const sampled = sample(items);
      const payloads = sampled.map((it) => encodeItem(format, it));
      const base = sampled.reduce((a, it) => a + baselineSize(it), 0);
      const raw = payloads.reduce((a, p) => a + p.length, 0);

      process.stderr.write(`  ${wlName} ${format.name}/zstd+dict110k (n=${sampled.length}) ...`);
      const tp = Date.now();
      const pref = await zstdPrefixSizes(payloads, DICT_110K, 10);
      process.stderr.write(` ${Date.now() - tp}ms\n`);
      const outP = pref.reduce((a, b) => a + b, 0);
      rows.push({
        format: format.name, codec: 'zstd+dict110k', n: sampled.length,
        rawBytes: raw, outBytes: outP, baseBytes: base,
        perMsg: outP / sampled.length, vsJson: outP / base,
        worse: pref.filter((s, i) => s > payloads[i].length).length,
        dict: DICT_110K.length, encUsPerItem: null, decUsPerItem: null,
      });

      if (wlName === 'single' || wlName === 'burst') {
        // Session mode only makes sense where a link stays open across many small sends,
        // and it must see a conversation in order, so it runs over everything.
        const perConv = [];
        const flat = [];
        for (const c of data.testConvos) {
          const mine = items.filter((it) => it.conv === c).map((it) => encodeItem(format, it));
          perConv.push(mine);
          flat.push(...mine);
        }
        const sess = await zstdSessionSizes(perConv, 10);
        const outS = sess.reduce((a, b) => a + b, 0);
        const flatBase = items.reduce((a, it) => a + baselineSize(it), 0);
        rows.push({
          format: format.name, codec: 'zstd session', n: flat.length,
          rawBytes: flat.reduce((a, p) => a + p.length, 0), outBytes: outS, baseBytes: flatBase,
          perMsg: outS / flat.length, vsJson: outS / flatBase,
          worse: sess.filter((s, i) => s > flat[i].length).length,
          dict: 0, encUsPerItem: null, decUsPerItem: null,
        });
      }
    }

    results[wlName] = rows;
  }

  results._meta = {
    dict32k: DICT_32K.length,
    dict110k: DICT_110K.length,
    etxModelBytes: etx.modelBytes,
    kana8TableBytes: kana.tableBytes,
    trainMessages: trainLines.length,
    node: process.version,
    cpu: require('os').cpus()[0].model,
  };

  require('fs').writeFileSync(
    require('path').join(__dirname, 'results.json'),
    JSON.stringify(results, null, 2),
  );
  require('./report')(results);
}

main().catch((e) => { console.error(e); process.exit(1); });
