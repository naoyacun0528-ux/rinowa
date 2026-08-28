'use strict';
/**
 * A harder corpus. This is W-3.
 *
 * ## What was wrong with the first one
 *
 * `corpus.js` drew every message from a pool of about 250 hand-written sentences. Over a
 * few thousand messages that means each sentence appears roughly ten times, so the test
 * split is full of text the dictionary has *literally seen*. That inflates every
 * dictionary method and says nothing about a real thread.
 *
 * Measured on the old corpus, only about half the test messages were novel. Here the
 * target is the opposite: **most messages should be strings that have never existed
 * before**, assembled from templates with many slots, the way real sentences are.
 *
 * ## What a real thread actually contains
 *
 * Not just Japanese prose. The mix below is set to include the things that break
 * compressors, not only the things that flatter them:
 *
 *  - **URLs** — near-random tokens. Incompressible by construction, and every real thread
 *    has them. A corpus without URLs overstates every codec.
 *  - **Numbers, times, prices** — digits carry no linguistic redundancy.
 *  - **Emoji** — four bytes each in UTF-8, and outside any Japanese-tuned byte table.
 *  - **English and romaji** — one byte per character already, so nothing to win.
 *  - **Very short acknowledgements** — where framing overhead decides everything.
 *  - **Long messages** — where LZ finally has room to work.
 *
 * ## The split
 *
 * Train and test draw from **disjoint template sets and disjoint content vocabulary**.
 * What they share is the closed class of words that genuinely recurs between strangers:
 * greetings, particles, acknowledgements. Splitting those too would model a world where
 * nobody ever says おはよう twice, which would understate dictionaries as badly as the
 * old corpus overstated them.
 */

const { makeRandom } = require('./corpus');

// ---------------------------------------------------------------------------------------
// Shared closed class — the words that recur no matter who is talking
// ---------------------------------------------------------------------------------------

const ACKS = [
  'うん', 'はい', 'おけ', 'OK', 'りょ', '了解', 'わかった', 'そうなんだ', 'なるほど',
  'たしかに', 'いいね', 'ありがとう', 'ありがと', 'ごめん', 'すまん', 'おつかれ',
  'おはよう', 'おやすみ', 'ただいま', 'おかえり', 'またね', 'よろしく', 'いってらっしゃい',
  'ほんと？', 'まじ？', 'えっ', 'あー', 'うーん', 'そっか', 'だよね', 'ですね',
  'かも', 'たぶん', 'あとで', 'いま？', 'どこ？', 'なんで？', 'いつ？', '大丈夫',
];

const EMOJI = ['😂', '🙏', '👍', '😅', '❤️', '🥲', '🎉', '😭', '🍜', '☺️', '🙇‍♀️', '👀', '💦', '✨'];
const TAILS = ['', '', '', '', '', '！', '〜', 'w', 'ww', '。', '？'];

// ---------------------------------------------------------------------------------------
// Template machinery
// ---------------------------------------------------------------------------------------

/**
 * Fills `{slot}` from the given vocabulary.
 *
 * The point of templates is combinatorial reach: eight slots with a dozen fillers each is
 * more distinct sentences than anyone can write by hand, and none of them is a string the
 * dictionary memorised.
 */
function fill(rnd, template, vocab) {
  return template.replace(/\{(\w+)\}/g, (_, key) => {
    const options = vocab[key];
    if (!options) throw new Error('no vocabulary for slot ' + key);
    return options[Math.floor(rnd() * options.length) % options.length];
  });
}

const TRAIN_VOCAB = {
  person: ['お母さん', 'お父さん', 'おばあちゃん', '田中さん', '課長', '先生', 'そうた', 'みゆき'],
  place: ['駅前', 'スーパー', '病院', '市民会館', '学校', '会社', '公園', '実家', 'コンビニ', '本屋'],
  food: ['カレー', 'うどん', 'ラーメン', '唐揚げ', 'サラダ', 'お好み焼き', '餃子', '親子丼'],
  thing: ['傘', '鍵', '充電器', '洗剤', '牛乳', '卵', 'お米', 'ティッシュ', '電池', 'ゴミ袋'],
  action: ['寄る', '行く', '買う', '取りに行く', '返す', '受け取る', '片付ける', '確認する'],
  time: ['七時', '八時半', '昼過ぎ', '夕方', '明日の朝', '今週末', '来週の火曜', '午後三時'],
  feeling: ['疲れた', '眠い', '暑い', '寒い', 'お腹すいた', '楽しかった', '助かった'],
};

const TEST_VOCAB = {
  person: ['山本さん', '部長', 'コーチ', 'ひなた', 'いとこ', '大家さん', '担任', '隣の人'],
  place: ['体育館', '市役所', '歯医者', '図書館', '駐車場', 'ホームセンター', '美容室', '整骨院'],
  food: ['焼きそば', 'グラタン', '天ぷら', 'ハンバーグ', '味噌汁', 'チャーハン', 'オムライス'],
  thing: ['自転車の鍵', '体操服', '印鑑', '保険証', '延長コード', 'マスク', '運動靴', '教科書'],
  action: ['提出する', '用意する', '持っていく', '直す', '伝えておく', '申し込む', '外す', '干す'],
  time: ['六時前', '九時ごろ', '正午', '深夜', '明後日', '月末', '再来週', '朝イチ'],
  feeling: ['緊張する', 'びっくりした', '安心した', '焦った', '嬉しい', 'しんどい'],
};

const TRAIN_TEMPLATES = [
  '{time}に{place}寄って{thing}買ってきて',
  '今日の晩ごはん{food}でいい？',
  '{person}から連絡あった？',
  '{time}には帰れると思う',
  '{thing}どこ置いたか覚えてる？',
  '{place}で{person}に会った',
  '{feeling}から先に休むね',
  '{thing}を{action}のお願いできる？',
  '{food}作りすぎたから食べて',
  '{time}の予定どうなった？',
  '{person}が{time}に来るって',
  '{place}まで{action}んだけど一緒にどう',
  'さっき{place}で{thing}見つけた',
  '{feeling}けど{time}までには終わる',
];

const TEST_TEMPLATES = [
  '{person}に{thing}渡しておいた',
  '{place}の予約{time}で取れた',
  '{food}温めて食べてね',
  '{thing}が見当たらないんだけど知らない？',
  '{time}から{place}行くことになった',
  '{person}が{feeling}って言ってた',
  '{place}混んでたから{time}にずらす',
  '{thing}の件、{person}に聞いてみて',
  '{feeling}ので{time}は無理そう',
  '{food}と{food}どっちがいい？',
  '{time}に{person}と{place}で待ち合わせ',
  '{thing}忘れずに持っていって',
  '{place}から{time}に出れば間に合うかな',
  '{person}の{thing}まだ返してない',
];

const LONG_TRAIN = [
  '明日の{time}なんだけど、{place}に行ってから{person}のところに寄る予定にしてる。{thing}も持っていかないといけないから、けっこう時間かかりそう。',
  'さっき伝え忘れたんだけど、{person}が{time}に来ることになったので、{food}を多めに作っておいてもらえると助かります。{feeling}ので手伝えなくてごめん。',
  '{place}の件、結局{time}に変更になりました。{thing}の準備だけお願いしたいのと、もし余裕があれば{person}にも共有しておいてほしい。',
];

const LONG_TEST = [
  '今日{place}に行ったら思ったより{feeling}状態で、{time}まで待つことになりそう。{food}は先に食べていてください、{thing}だけ出しておいてもらえると助かる。',
  '{person}と話したんだけど、{time}のほうが都合がいいみたい。{place}の予約も取り直しておいたので、{thing}の準備だけよろしくお願いします。',
  'すこし長くなるけど、{place}で{person}に会って{thing}の話をしてきました。結論としては{time}までに決めれば大丈夫とのことなので、あわてなくていいと思う。',
];

const ENGLISH = [
  'sounds good', 'on my way', 'be there in 10', 'thanks!', 'no worries', 'lol same',
  'can you check this', 'sorry just saw this', 'see you tomorrow', 'let me know',
  'I will send it later', 'got it thanks', 'that works for me', 'running late sorry',
  'happy birthday!!', 'good luck today', 'call me when you can',
];

const URL_HOSTS = ['youtu.be', 'www.youtube.com', 'amzn.to', 'twitter.com', 'news.example.co.jp', 'maps.app.goo.gl', 'docs.google.com'];
const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-';

function makeUrl(rnd) {
  const host = URL_HOSTS[Math.floor(rnd() * URL_HOSTS.length)];
  let token = '';
  const len = 8 + Math.floor(rnd() * 16);
  for (let i = 0; i < len; i++) token += B64[Math.floor(rnd() * B64.length)];
  const prefix = rnd() < 0.4 ? '/watch?v=' : '/';
  return `https://${host}${prefix}${token}`;
}

function makeNumeric(rnd) {
  const kind = Math.floor(rnd() * 5);
  const n = (a, b) => a + Math.floor(rnd() * (b - a));
  if (kind === 0) return `${n(1, 13)}月${n(1, 29)}日 ${n(8, 22)}:${String(n(0, 60)).padStart(2, '0')}`;
  if (kind === 1) return `${n(300, 9800)}円だった`;
  if (kind === 2) return `090-${String(n(1000, 9999))}-${String(n(1000, 9999))}`;
  if (kind === 3) return `${n(1, 5)}人で${n(2, 6)}時間くらい`;
  return `注文番号 ${n(100000, 999999)}-${n(10, 99)}`;
}

/**
 * One message.
 *
 * The proportions are the point. Roughly a third of real chat is an acknowledgement of six
 * characters or fewer, and that is exactly the size where framing overhead decides whether
 * a codec helps or hurts — so under-representing it would hide the finding that matters.
 */
function makeLine(rnd, templates, vocab, longTemplates) {
  const r = rnd();
  let line;
  if (r < 0.34) {
    line = ACKS[Math.floor(rnd() * ACKS.length)];
  } else if (r < 0.74) {
    line = fill(rnd, templates[Math.floor(rnd() * templates.length)], vocab);
  } else if (r < 0.86) {
    line = fill(rnd, longTemplates[Math.floor(rnd() * longTemplates.length)], vocab);
  } else if (r < 0.91) {
    line = ENGLISH[Math.floor(rnd() * ENGLISH.length)];
  } else if (r < 0.94) {
    line = makeUrl(rnd);
  } else if (r < 0.99) {
    line = makeNumeric(rnd);
  } else {
    // Emoji-only. People send these on their own, and three or four of them is a message
    // made entirely of four-byte codepoints.
    const count = 1 + Math.floor(rnd() * 4);
    line = Array.from({ length: count }, () => EMOJI[Math.floor(rnd() * EMOJI.length)]).join('');
    return line;
  }

  if (rnd() < 0.22) line += EMOJI[Math.floor(rnd() * EMOJI.length)];
  else line += TAILS[Math.floor(rnd() * TAILS.length)];
  return line;
}

function buildThread(rnd, templates, vocab, longTemplates, turns) {
  const lines = [];
  for (let i = 0; i < turns; i++) lines.push(makeLine(rnd, templates, vocab, longTemplates));
  return lines;
}

function buildCorpus2() {
  const rnd = makeRandom(0xC0FFEE);
  const train = [];
  const test = [];
  for (let i = 0; i < 40; i++) {
    train.push(buildThread(rnd, TRAIN_TEMPLATES, TRAIN_VOCAB, LONG_TRAIN, 60 + Math.floor(rnd() * 80)));
  }
  for (let i = 0; i < 20; i++) {
    test.push(buildThread(rnd, TEST_TEMPLATES, TEST_VOCAB, LONG_TEST, 60 + Math.floor(rnd() * 80)));
  }
  return { train, test };
}

/** How hard is this corpus, really? Reported alongside every result that uses it. */
function corpusStats(corpus) {
  const trainLines = corpus.train.flat();
  const testLines = corpus.test.flat();
  const trainSet = new Set(trainLines);
  const novel = testLines.filter((l) => !trainSet.has(l));
  const chars = testLines.reduce((a, l) => a + [...l].length, 0);
  const bytes = testLines.reduce((a, l) => a + Buffer.byteLength(l, 'utf8'), 0);
  const lengths = testLines.map((l) => [...l].length).sort((a, b) => a - b);
  return {
    trainLines: trainLines.length,
    testLines: testLines.length,
    uniqueTest: new Set(testLines).size,
    novelFraction: novel.length / testLines.length,
    medianChars: lengths[Math.floor(lengths.length / 2)],
    p90Chars: lengths[Math.floor(lengths.length * 0.9)],
    meanBytes: bytes / testLines.length,
    bytesPerChar: bytes / chars,
  };
}

module.exports = { buildCorpus2, corpusStats };
