'use strict';
/**
 * The text Echo actually has to carry.
 *
 * ## Why this file is hand-written and not scraped
 *
 * There is no public corpus of Japanese family chat, and the ones that exist for Twitter or
 * BBS text have the wrong shape: longer lines, more nouns, far fewer of the two-character
 * acknowledgements that make up most of a real thread. Getting the *length distribution*
 * right matters more here than getting the vocabulary right, because the whole question
 * under study is what happens to payloads of twenty bytes.
 *
 * ## The honesty problem, and what is done about it
 *
 * Any hand-written corpus is more repetitive than reality, and repetition is exactly what a
 * compressor is measured on. Numbers from a corpus that quietly repeats itself are
 * flattering and useless. Two things guard against it:
 *
 *  1. **Disjoint topic pools.** `TRAIN_TOPICS` and `TEST_TOPICS` share no sentences. A
 *     dictionary trained on one is evaluated against text it has never seen.
 *  2. **A shared everyday pool on purpose.** Greetings and acknowledgements *are* shared
 *     between the two, because that is true of real conversation — a new thread still opens
 *     with おはよう. Removing them would understate a dictionary as much as duplicating the
 *     topics would overstate it.
 *
 * The remaining bias is stated in the results rather than hidden: a real corpus would have
 * a longer tail of rare words, so the absolute ratios here are optimistic for every codec.
 * What survives the bias is the *comparison between codecs*, which is what is being asked.
 */

/** Openers, closers, and acknowledgements. Shared between train and test — see above. */
const EVERYDAY = [
  'おはよう', 'おはようございます', 'おやすみ', 'おやすみなさい', 'ただいま', 'おかえり',
  'いってきます', 'いってらっしゃい', 'ありがとう', 'ありがとうございます', 'ごめん',
  'ごめんね', 'すみません', 'よろしく', 'よろしくお願いします', 'お疲れさま',
  'お疲れ様でした', 'はい', 'うん', 'いいよ', 'だめ', 'わかった', 'わかりました',
  'りょうかい', '了解', 'ok', 'OK', 'おけ', 'わろた', 'まじで', 'まじか', 'そうなんだ',
  'そうなんですね', 'なるほど', 'たしかに', 'いいね', 'すごい', 'すごいね', 'えらい',
  'がんばって', 'がんばれ', 'おめでとう', 'おつかれ', 'ほんと', 'ほんとに', 'たぶん',
  'かも', 'かもね', 'だよね', 'ですね', 'ですよね', 'そっか', 'あー', 'えー', 'うそ',
  'やった', 'よかった', '助かる', 'たすかる', '大丈夫', '大丈夫？', '平気', 'ごめんなさい',
  'ちょっと待って', 'あとで', 'あとでね', 'またね', 'じゃあね', 'バイバイ', 'いま？',
  'なに', 'なんで', 'どこ', 'いつ', 'だれ', 'どうやって', 'いくら', 'どれ', 'これ',
  'それ', 'あれ', 'うんうん', 'ふーん', 'へー', 'まあね', 'いいえ', 'ちがう', 'ちがうよ',
];

/** Conversations the dictionary is allowed to learn from. */
const TRAIN_TOPICS = [
  '今日の夕飯なににする', 'カレーでいい？', 'スーパー寄って帰るね', '牛乳買ってきて',
  '卵切らしてた', 'お米あと少しだよ', '洗剤も無くなりそう', '冷蔵庫に入れといたよ',
  'レンジであっためて食べて', '先に食べてて', '何時ごろ帰る？', '七時には着くと思う',
  '電車遅れてる', '事故で止まってるみたい', 'タクシーで帰るね', '駅まで迎えに行こうか',
  '大丈夫、歩ける', '傘持ってったほうがいい', '雨降ってきた', '洗濯物取り込んで',
  '風強いね', '明日は晴れるって', '今週ずっと雨らしい', '暑すぎる', 'エアコンつけていい？',
  '寒くない？', '上着いる？', '体調どう？', '熱測った？', '三十七度五分あった',
  '病院行ったほうがいいよ', '薬飲んだ', '寝てなよ', '無理しないで', '仕事終わった',
  '今日残業', '会議が長引いた', '明日は早番', '有給とれそう', '来週休みとった',
  '旅行どこ行く', '温泉行きたい', '飛行機の予約した', 'ホテル取っておくね',
  '写真送るね', 'かわいい', 'よく撮れてる', 'これ見て', 'あとで送る', '動画重いかも',
  '宿題やった？', 'テストどうだった', '八十点だった', '難しかった', '部活何時まで',
  '迎え行くよ', '友達の家に寄る', '六時までには帰る', '塾の日だっけ', '弁当いる？',
  '給食だから大丈夫', '運動会の日程きた', '参観日行けそう', '三者面談いつ',
  'おじいちゃんに電話した', 'おばあちゃん元気だった', '来月帰省する？', 'お墓参り行こう',
  '正月どうする', 'お年玉用意しないと', '年賀状出した？', '大掃除手伝って',
  'ゴミ出しといて', '燃えるゴミ明日だよ', '布団干した', '掃除機かけといた',
  'ネットが遅い', 'ルーター再起動して', 'パスワード何だっけ', '充電器どこ',
  'リモコン見つからない', 'テレビつけて', 'あの番組録画した？', '見逃した',
  '銀行行ってきた', '振込しといた', '家賃引き落とし今日', '電気代高くなったね',
  '保険の書類きてる', '印鑑どこにある', '確定申告めんどくさい', 'レシート取っといて',
];

/** Held out. The dictionary has never seen a word of this. */
const TEST_TOPICS = [
  '猫がまた脱走した', '首輪付け直さないと', '動物病院の予約とった', 'ワクチン今月だって',
  '餌の種類変えてみる', '爪切り嫌がる', 'ケージ買い替えたい', '砂の減りが早い',
  '自転車のタイヤ空気抜けてる', '空気入れ借りていい？', 'パンクしたかも',
  '修理いくらだった', '駐輪場いっぱいだった', '鍵かけ忘れた', '防犯登録どこでやる',
  'ギター弦切れた', '練習付き合って', '発表会いつだっけ', '楽譜プリントして',
  '音大きすぎ？', 'ヘッドホン使う', 'レッスン代振り込んだ', '先生が変わるらしい',
  '本屋で予約してきた', '発売日は来週', '続き気になる', '貸してあげる', '返すの忘れてた',
  '図書館の返却期限今日', '延長できるって', '読み終わった感想聞かせて',
  'パン焼いてみた', '膨らまなかった', 'イースト入れ忘れ', '型が小さかったかも',
  '次はうまくいくよ', 'オーブンの温度下げて', 'レシピ送って', 'まあまあの味',
  '庭の草がすごい', '草むしり手伝って', '虫刺された', '薬塗った', '軍手ある？',
  '植木鉢割れちゃった', '水やり頼める', '枯れかけてる', '肥料買ってくる',
  '車検の案内きた', '見積もり取った', 'タイヤ交換の時期', 'オイル漏れてるって',
  '代車出してくれる', '来週の土曜に入れた', 'ナビの地図が古い',
  '歯医者予約変更した', '親知らず抜くって', '腫れてる', '柔らかいもの食べる',
  '麻酔切れてきた', '痛み止め飲んだ', '来週抜糸',
  '引っ越しの見積もり', '段ボールもらってきた', '不用品どうする', 'リサイクル出す',
  '住所変更の手続き', '電気の停止連絡した', 'ネット開通いつ', '鍵の受け渡し明日',
];

/** Longer turns. Short chat is the common case, but not the only one. */
const LONG_LINES = [
  '明日の予定なんだけど、午前中は病院で午後から買い物に行こうと思ってるから、もし時間あったら一緒に行かない？',
  'さっき言い忘れたけど、来週の水曜日は学校が振替休日らしくて、その日どこか連れて行ってあげたいなと思ってる',
  'ごめん、今日はどうしても仕事が終わらなくて遅くなりそう。夕飯は先に食べてもらって大丈夫だから、片付けだけお願いしてもいい？',
  '写真ありがとう、すごくよく撮れてるね。おばあちゃんにも見せたいから、あとでプリントできるサイズのやつも送ってもらえると助かります',
  '来月の旅行の件だけど、宿は二泊で予約したよ。朝食付きにしたから、初日の夕飯だけ外で食べる感じでいいかな。移動は車のほうが楽だと思う',
];

/** Not everything is Japanese. */
const MIXED = [
  'https://example.com/a/b?c=1', 'OK!', 'thanks', 'see you', 'lol',
  '会議は 10:30 から', 'PDFを送ります', 'zoomのリンク送って', '1200円だった',
  '2026/09/14 19:00', 'TEL 090-1234-5678', 'AmazonでKindle版が出てる',
];

const EMOJI_TAILS = ['', '', '', '', '😂', '🙏', '👍', '😅', '❤️', '🥲', '！', '〜', 'w'];

/**
 * A deterministic generator.
 *
 * Seeded rather than random so a change in a codec's score is always the codec's doing and
 * never a different draw of the corpus. Every run must be comparable to the last.
 */
function makeRandom(seed) {
  let s = seed >>> 0;
  return function next() {
    s ^= s << 13; s >>>= 0;
    s ^= s >>> 17;
    s ^= s << 5; s >>>= 0;
    return s / 4294967296;
  };
}

function pick(rnd, arr) {
  return arr[Math.floor(rnd() * arr.length) % arr.length];
}

/**
 * Builds one thread's worth of lines.
 *
 * The mix — mostly everyday, some topical, occasionally long — is set to land the median
 * message near twenty characters, which is where real chat sits and where every general
 * purpose compressor is at its weakest.
 */
function buildThread(rnd, topics, turns) {
  const lines = [];
  for (let i = 0; i < turns; i++) {
    const r = rnd();
    let line;
    if (r < 0.44) line = pick(rnd, EVERYDAY);
    else if (r < 0.86) line = pick(rnd, topics);
    else if (r < 0.94) line = pick(rnd, MIXED);
    else line = pick(rnd, LONG_LINES);

    const tail = pick(rnd, EMOJI_TAILS);
    lines.push(line + tail);
  }
  return lines;
}

/** @returns {{train: string[][], test: string[][]}} threads of lines. */
function buildCorpus() {
  const rnd = makeRandom(0x5EED);
  const train = [];
  const test = [];
  for (let i = 0; i < 40; i++) train.push(buildThread(rnd, TRAIN_TOPICS, 60 + Math.floor(rnd() * 80)));
  for (let i = 0; i < 20; i++) test.push(buildThread(rnd, TEST_TOPICS, 60 + Math.floor(rnd() * 80)));
  return { train, test };
}

module.exports = { buildCorpus, makeRandom, pick, EVERYDAY, TRAIN_TOPICS, TEST_TOPICS };
