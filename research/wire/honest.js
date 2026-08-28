'use strict';
/**
 * 78% はどこから来たのか。**手柄を分けて数える。**
 *
 * JSON と比べた削減率を一つの数字にすると、
 * 「フィールド名を落としただけで誰でも取れる分」と
 * 「Yosegi 自身の考えが稼いだ分」が混ざったまま出てしまう。
 *
 * ここでは三段で測る:
 *
 *   JSON            項目名を毎回持つ。今 Firestore に入っている形そのまま。
 *   MessagePack     項目名は持つが、区切りと数値が詰まる。実際によく使われる形。
 *   素朴バイナリ     項目名を落として順番を決め打ちにしただけ。それ以上はしない。
 *   Yosegi          そこから先の考えを全部入れたもの。
 *
 * 出すべき数字は「JSON より何%」ではなく「**素朴バイナリより何%**」。
 * 前者は Yosegi が無くても取れる。
 */

const { pack } = require('msgpackr');
const cborx = require('cbor-x');
const dataset = require('./dataset');
const yosegi = require('./yosegi');
const naive = require('./naive');
const { STICKER_IDS } = require('./dataset');
const { buildCorpus } = require('./corpus');
const { buildCorpus2, corpusStats } = require('./corpus2');

const out = [];
const say = (s) => { out.push(s); console.log(s); };

function run(label, corpus, mode) {
  const data = dataset.build(corpus);
  const stats = corpusStats(corpus);

  const items = [];
  for (const c of data.testConvos) {
    const ctx = yosegi.makeContext(c.ctx.conversationId, c.ctx.members, STICKER_IDS);
    if (mode === 'batch') items.push({ msgs: c.messages, ctx });
    else for (const m of c.messages) items.push({ msgs: [m], ctx });
  }

  const total = { JSON: 0, MessagePack: 0, CBOR: 0, naive: 0, Yosegi: 0 };
  for (const it of items) {
    total.JSON += Buffer.byteLength(JSON.stringify(it.msgs), 'utf8');
    total.MessagePack += pack(it.msgs).length;
    total.CBOR += cborx.encode(it.msgs).length;
    total.naive += naive.encode(it.msgs).length;
    total.Yosegi += yosegi.encode(it.msgs, it.ctx).length;
  }

  // 読み戻せることを確かめてから数える。書けるだけの形は比較対象にならない。
  for (const it of items.slice(0, 200)) {
    const back = naive.decode(naive.encode(it.msgs));
    const a = JSON.stringify(back), b = JSON.stringify(it.msgs);
    if (a !== b) throw new Error('素朴バイナリが読み戻せていない:\n' + a + '\n' + b);
  }

  const n = items.reduce((a, it) => a + it.msgs.length, 0);
  const per = (k) => total[k] / n;
  const pct = (a, b) => ((1 - per(a) / per(b)) * 100).toFixed(1);

  say('');
  say('='.repeat(72));
  say(`  ${label}`);
  say('='.repeat(72));
  say(`  ${n} 件（${items.length} 回に分けて送信）· 本文は平均 ${stats ? '' : ''}${(stats.meanBytes ?? 0).toFixed(1)} バイト · 未知の文 ${(stats.novelFraction * 100).toFixed(0)}%`);
  say('');
  say('  形式             1件あたり   JSON比   素朴バイナリ比');
  say('  ' + '-'.repeat(56));
  for (const k of ['JSON', 'MessagePack', 'CBOR', 'naive', 'Yosegi']) {
    const label = k === 'naive' ? '素朴バイナリ' : k;
    const pad = label + ' '.repeat(Math.max(0, 16 - [...label].reduce((w, c) => w + (c.charCodeAt(0) > 0x2000 ? 2 : 1), 0)));
    say(`  ${pad} ${per(k).toFixed(1).padStart(7)} B  ${(per(k) / per('JSON') * 100).toFixed(1).padStart(6)}%  ${(per(k) / per('naive') * 100).toFixed(1).padStart(9)}%`);
  }
  say('');
  say('  手柄の内訳（JSON からの削減 100 のうち）:');
  const jsonToNaive = per('JSON') - per('naive');
  const naiveToYosegi = per('naive') - per('Yosegi');
  const whole = per('JSON') - per('Yosegi');
  say(`    項目名を落としただけ    ${(jsonToNaive / whole * 100).toFixed(0)}`);
  say(`    Yosegi 自身の考え       ${(naiveToYosegi / whole * 100).toFixed(0)}`);
  say('');
  say(`  → JSON より ${pct('Yosegi', 'JSON')}% 小さい`);
  say(`  → MessagePack より ${pct('Yosegi', 'MessagePack')}% 小さい`);
  say(`  → 素朴バイナリより ${pct('Yosegi', 'naive')}% 小さい  ← **これが Yosegi の取り分**`);

  return { per: Object.fromEntries(Object.keys(total).map((k) => [k, per(k)])) };
}

run('1件ずつ送る · 繰り返しの多いコーパス', buildCorpus(), 'single');
run('1件ずつ送る · 現実に近いコーパス', buildCorpus2(), 'single');
run('まとめて送る · 繰り返しの多いコーパス', buildCorpus(), 'batch');
run('まとめて送る · 現実に近いコーパス', buildCorpus2(), 'batch');

require('fs').writeFileSync(require('path').join(__dirname, 'honest.txt'), out.join('\n'));
