'use strict';
/**
 * 素朴な位置決め打ちバイナリ。**Yosegi の対抗馬として置く藁人形。**
 *
 * 出発点は、友達に指摘されたこの一言:
 *
 *   「JSON が毎回フィールド名を持っているなら当然かなり冗長で、
 *     Yosegi 側が [id][sender][timestamp][payload] のようなバイナリなら、
 *     JSON の構造上の冗長性を取り除くだけでも大幅に小さくできる」
 *
 * まったくその通り。なので**それだけをやる形**を実際に作って測る。
 * 項目の順番を決め打ちにして名前を落とす。それ以上は何もしない:
 *
 *  - id は 20文字の ASCII のまま。base62 として詰め直さない。
 *  - conversationId は毎回入れる。フレームの外に括り出さない。
 *  - senderId は 28文字のまま。会話の参加者表の何番目か、にはしない。
 *  - 時刻は 8バイト固定。前のメッセージとの差にしない。
 *  - stickerId は文字列のまま。目録の何番目か、にはしない。
 *
 * こうして測った差が「名前を落としただけで得られる分」で、
 * Yosegi との差が「Yosegi 自身の考えが稼いだ分」になる。
 * 一つの数字にまとめると、どちらの手柄か分からなくなる。
 */

const STATUS = ['Sent', 'Delivered', 'Read', 'Failed'];

function str(s) { return Buffer.from(s, 'utf8'); }

function encode(messages) {
  const parts = [];
  const head = Buffer.alloc(2);
  head.writeUInt16LE(messages.length, 0);
  parts.push(head);

  for (const m of messages) {
    const id = str(m.id), conv = str(m.conversationId);
    const sid = str(m.senderId), name = str(m.senderName);

    parts.push(Buffer.from([id.length]), id);
    parts.push(Buffer.from([conv.length]), conv);
    parts.push(Buffer.from([sid.length]), sid);
    parts.push(Buffer.from([name.length]), name);

    const ts = Buffer.alloc(8);
    ts.writeBigUInt64LE(BigInt(m.timestampMs), 0);
    parts.push(ts);

    const statusIndex = Math.max(0, STATUS.indexOf(m.status));
    const hasText = m.text !== undefined;
    const hasSticker = m.stickerId !== undefined;
    const hasReply = m.replyTo !== undefined;
    const reactionKeys = m.reactions ? Object.keys(m.reactions) : [];
    const flags = (hasText ? 1 : 0) | (hasSticker ? 2 : 0)
                | (hasReply ? 4 : 0) | (reactionKeys.length ? 8 : 0);
    parts.push(Buffer.from([statusIndex, flags]));

    if (hasText) {
      const t = str(m.text);
      const len = Buffer.alloc(2); len.writeUInt16LE(t.length, 0);
      parts.push(len, t);
    }
    if (hasSticker) {
      const s = str(m.stickerId);
      parts.push(Buffer.from([s.length]), s);
    }
    if (hasReply) {
      const rid = str(m.replyTo.messageId);
      const rname = str(m.replyTo.senderName);
      const rex = str(m.replyTo.excerpt);
      parts.push(Buffer.from([rid.length]), rid);
      parts.push(Buffer.from([rname.length]), rname);
      parts.push(Buffer.from([rex.length]), rex);
    }
    if (reactionKeys.length) {
      parts.push(Buffer.from([reactionKeys.length]));
      for (const k of reactionKeys) {
        const u = str(k);
        parts.push(Buffer.from([u.length]), u, Buffer.from([m.reactions[k]]));
      }
    }
  }
  return Buffer.concat(parts);
}

/** 読み戻せることまで確かめる。書けるだけの形を比較対象にしても意味がない。 */
function decode(buf) {
  let o = 0;
  const count = buf.readUInt16LE(o); o += 2;
  const out = [];
  const take = () => { const n = buf[o]; o += 1; const s = buf.toString('utf8', o, o + n); o += n; return s; };

  for (let i = 0; i < count; i++) {
    const m = {
      id: take(),
      conversationId: take(),
      senderId: take(),
      senderName: take(),
    };
    m.timestampMs = Number(buf.readBigUInt64LE(o)); o += 8;
    m.status = STATUS[buf[o]]; o += 1;
    const flags = buf[o]; o += 1;

    if (flags & 1) { const n = buf.readUInt16LE(o); o += 2; m.text = buf.toString('utf8', o, o + n); o += n; }
    if (flags & 2) m.stickerId = take();
    if (flags & 4) m.replyTo = { messageId: take(), senderName: take(), excerpt: take() };
    if (flags & 8) {
      const n = buf[o]; o += 1;
      m.reactions = {};
      for (let j = 0; j < n; j++) { const k = take(); m.reactions[k] = buf[o]; o += 1; }
    }
    out.push(m);
  }
  return out;
}

module.exports = { encode, decode };
