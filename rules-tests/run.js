/**
 * firestore.rules の、実行できる証明。
 *
 * docs/ROADMAP.md は Prototype 1 の完了条件として2つを必須にしている:
 *
 *   - 会話参加者以外が本文を読めないこと
 *   - 管理者アカウントに本文への特権的読み取り経路が存在しないこと
 *
 * 「正しそうに見える」ルールファイルは、どちらの証拠にもならない。ここでは本物の
 * ルールを本物の Firestore エミュレータに当てて確かめる。
 *
 * 実行:  npm test  （rules-tests/ の中で）
 */

import { readFileSync } from 'node:fs';
import {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} from '@firebase/rules-unit-testing';
import {
  Bytes,
  deleteField,
  doc,
  getDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  collection,
  addDoc,
  getDocs,
  query,
  where,
  setLogLevel,
} from 'firebase/firestore';

setLogLevel('error');

const ALICE = 'uid_alice';
const BOB = 'uid_bob';
const MALLORY = 'uid_mallory';
const CONVERSATION = 'conv_alice_bob';
// 脱退は会話の状態を変えてしまうので、そのための会話を分けて持つ。
const LEAVING = 'conv_leaving';
const LEAVING2 = 'conv_leaving_2';

let passed = 0;
let failed = 0;

async function check(name, fn) {
  try {
    await fn();
    passed++;
    console.log(`  PASS  ${name}`);
  } catch (error) {
    failed++;
    console.log(`  FAIL  ${name}`);
    console.log(`        ${error.message}`);
  }
}

const testEnv = await initializeTestEnvironment({
  projectId: 'echo-rules-test',
  firestore: {
    rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'),
  },
});

await testEnv.clearFirestore();

// 準備は admin で書く（ルールを迂回する）。ルールが何を許すかに関係なく、
// 前提データが存在するように。
await testEnv.withSecurityRulesDisabled(async (context) => {
  const db = context.firestore();
  await setDoc(doc(db, 'users', ALICE), {
    displayName: 'Alice',
    photoUrl: null,
    inviteCode: 'AAAA1111',
    createdAt: new Date(),
    updatedAt: new Date(),
  });
  await setDoc(doc(db, 'users', BOB), {
    displayName: 'Bob',
    photoUrl: null,
    inviteCode: 'BBBB2222',
    createdAt: new Date(),
    updatedAt: new Date(),
  });
  await setDoc(doc(db, 'inviteCodes', 'BBBB2222'), { uid: BOB, createdAt: new Date() });
  await setDoc(doc(db, 'conversations', CONVERSATION), {
    type: 'direct',
    title: null,
    memberIds: [ALICE, BOB],
    acceptedBy: [ALICE],
    createdAt: new Date(),
    updatedAt: new Date(),
    lastMessageAt: new Date(),
    lastMessage: null,
  });
  for (const id of [LEAVING, LEAVING2]) {
    await setDoc(doc(db, 'conversations', id), {
      type: 'direct',
      title: null,
      memberIds: [ALICE, BOB],
      acceptedBy: [ALICE, BOB],
      createdAt: new Date(),
      updatedAt: new Date(),
      lastMessageAt: new Date(),
      lastMessage: null,
    });
  }
  await setDoc(doc(db, 'conversations', CONVERSATION, 'messages', 'msg1'), {
    senderId: ALICE,
    kind: 'text',
    text: 'これは本文です',
    sentAt: new Date(),
    reactions: {},
  });
});

const alice = testEnv.authenticatedContext(ALICE).firestore();
const bob = testEnv.authenticatedContext(BOB).firestore();
const mallory = testEnv.authenticatedContext(MALLORY).firestore();
const anonymous = testEnv.unauthenticatedContext().firestore();

console.log('\nMESSAGE BODIES');

await check('participant can read a message', () =>
  assertSucceeds(getDoc(doc(bob, 'conversations', CONVERSATION, 'messages', 'msg1'))));

await check('NON-participant cannot read a message', () =>
  assertFails(getDoc(doc(mallory, 'conversations', CONVERSATION, 'messages', 'msg1'))));

await check('signed-out cannot read a message', () =>
  assertFails(getDoc(doc(anonymous, 'conversations', CONVERSATION, 'messages', 'msg1'))));

await check('NON-participant cannot list the thread', () =>
  assertFails(getDocs(collection(mallory, 'conversations', CONVERSATION, 'messages'))));

await check('NON-participant cannot read the conversation itself', () =>
  assertFails(getDoc(doc(mallory, 'conversations', CONVERSATION))));

await check('NON-participant cannot write into the conversation', () =>
  assertFails(addDoc(collection(mallory, 'conversations', CONVERSATION, 'messages'), {
    senderId: MALLORY,
    kind: 'text',
    text: 'intrusion',
    sentAt: new Date(),
    reactions: {},
  })));

console.log('\nNO ADMIN BACK DOOR');

// staff のクレームも、admin の uid も、それを認める規則も無い。管理者を名乗る
// トークンも、結局はサインインした他人でしかない。
const fakeAdmin = testEnv
  .authenticatedContext('uid_fake_admin', { admin: true, staff: true, role: 'support' })
  .firestore();

await check('a token claiming admin cannot read a message', () =>
  assertFails(getDoc(doc(fakeAdmin, 'conversations', CONVERSATION, 'messages', 'msg1'))));

await check('a token claiming admin cannot list conversations', () =>
  assertFails(getDocs(collection(fakeAdmin, 'conversations'))));

console.log('\nMESSAGES ARE WRITE-ONCE');

await check('sender cannot edit the text after sending', () =>
  assertFails(updateDoc(doc(alice, 'conversations', CONVERSATION, 'messages', 'msg1'), {
    text: 'rewritten',
  })));

await check('participant can add their own reaction', () =>
  assertSucceeds(updateDoc(doc(bob, 'conversations', CONVERSATION, 'messages', 'msg1'), {
    [`reactions.${BOB}`]: 2,
  })));

await check("participant cannot write somebody else's reaction", () =>
  assertFails(updateDoc(doc(bob, 'conversations', CONVERSATION, 'messages', 'msg1'), {
    [`reactions.${ALICE}`]: 3,
  })));

await check('cannot send a message as somebody else', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'), {
    senderId: BOB,
    kind: 'text',
    text: 'forged',
    sentAt: new Date(),
    reactions: {},
  })));

await check('cannot send a body over the length limit', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'), {
    senderId: ALICE,
    kind: 'text',
    text: 'x'.repeat(4001),
    sentAt: new Date(),
    reactions: {},
  })));

console.log('\nPHOTOS');

// 写真のメッセージは id と形と、ドキュメントに入る大きさのサムネイルを持つ。
// そのサムネイルが、Firestore の中で画像の中身が許される*唯一の*場所なので、
// 大きさの上限が防御のすべてになる。上限が無ければ、その項目は、完全な画像でも
// 何でもメッセージに押し込める穴になる。docs/MEDIA_ARCHITECTURE.md §4。

const HASH = 'a'.repeat(64);
const photo = (overrides = {}) => ({
  senderId: ALICE,
  kind: 'image',
  mediaId: HASH,
  mediaW: 2048,
  mediaH: 1536,
  mediaBytes: 412000,
  mediaThumb: Bytes.fromUint8Array(new Uint8Array(4096)),
  sentAt: new Date(),
  reactions: {},
  ...overrides,
});

await check('can send a photo', () =>
  assertSucceeds(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'), photo())));

await check('cannot push a whole image through the thumbnail field', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    photo({ mediaThumb: Bytes.fromUint8Array(new Uint8Array(8193)) }))));

await check('a photo id must be a content hash', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    photo({ mediaId: 'not-a-hash' }))));

await check('a photo must declare its shape', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    photo({ mediaW: 0 }))));

await check('a thumbnail must be bytes, not a string pretending to be one', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    photo({ mediaThumb: 'pretend' }))));

await check('cannot send a photo as somebody else', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    photo({ senderId: BOB }))));

// ---------------------------------------------------------------------------------
// 通話の記録。
//
// 通話はスレッドに1行残す。本文は持たず、enum 2つと時間だけ。ここのルールは、
// それをそのまま保つことと、時間が人間が実際に話しうる数であることについて。
// 上限が無ければ記録は記録でなくなり、主張になる（1兆秒の通話を書けてしまう）。

const call = (overrides = {}) => ({
  senderId: ALICE,
  kind: 'call',
  callKind: 'audio',
  callOutcome: 'completed',
  callSeconds: 192,
  sentAt: new Date(),
  reactions: {},
  ...overrides,
});

await check('can leave a call record', () =>
  assertSucceeds(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'), call())));

await check('a call record cannot claim an impossible duration', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    call({ callSeconds: 999999999 }))));

await check('a call record cannot have a negative duration', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    call({ callSeconds: -1 }))));

await check('a call record cannot invent an outcome', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    call({ callOutcome: 'answered-by-the-fbi' }))));

await check('a call record cannot invent a kind', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    call({ callKind: 'hologram' }))));

await check('a call record cannot smuggle a body through the duration field', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    call({ callSeconds: 'we talked about the surprise party' }))));

await check('cannot write a call record as somebody else', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    call({ senderId: BOB }))));

// ---------------------------------------------------------------------------------
// 暗号化されたメッセージ。
//
// このテストは薄く見えるが、それが要点。**ルールはもう本文を検査できない。**
// Megolm の暗号文で、鍵は参加者の端末にしか無い。以前ここで守っていたこと
// （本文の長さ制限、サムネイル項目に画像を入れさせない）は、いまは「鍵の無い者には
// そもそも読めない」という事実が守っている。
//
// 残っているのは、形を悪用させないこと。暗号文は文字列であること、空でないこと、
// 上限があること。検査できないことは、無制限にしてよい理由にはならない。

const enc = (overrides = {}) => ({
  senderId: ALICE,
  kind: 'enc',
  ciphertext: '{"algorithm":"m.megolm.v1.aes-sha2","ciphertext":"AwgAEn..."}',
  sentAt: new Date(),
  reactions: {},
  ...overrides,
});

await check('can send an encrypted message', () =>
  assertSucceeds(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'), enc())));

await check('an encrypted message cannot be empty', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    enc({ ciphertext: '' }))));

await check('an encrypted message has a ceiling', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    enc({ ciphertext: 'x'.repeat(65537) }))));

await check('a ciphertext must be a string, not bytes smuggled in', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    enc({ ciphertext: Bytes.fromUint8Array(new Uint8Array(64)) }))));

await check('cannot send an encrypted message as somebody else', () =>
  assertFails(addDoc(collection(alice, 'conversations', CONVERSATION, 'messages'),
    enc({ senderId: BOB }))));

await check('a non-member cannot read an encrypted message', async () => {
  let id;
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const ref = await addDoc(
      collection(ctx.firestore(), 'conversations', CONVERSATION, 'messages'), enc());
    id = ref.id;
  });
  // Carol はどの会話にもいない。暗号文を得ても彼女には無意味だが、
  // ここで確かめるのは、そもそも集められないこと。
  await assertFails(getDoc(doc(mallory, 'conversations', CONVERSATION, 'messages', id)));
});


// ---------------------------------------------------------------------------------
// E2EE の鍵の運搬層。
//
// 暗号そのものは Rust のライブラリが持ちます。ここで守るのは「誰が何を読めるか」で、
// それは規則にしか書けません。E2EE を入れても、鍵の置き場所を間違えれば同じことです。
//
// 3つのコレクションで、読める範囲が全部違います。そこが要点です。

await check('anybody signed in can read a device identity key', () =>
  assertSucceeds(getDoc(doc(bob, 'users', ALICE, 'cryptoDevices', 'DEV1'))));

await check('cannot publish a device identity key as somebody else', () =>
  assertFails(setDoc(doc(bob, 'users', ALICE, 'cryptoDevices', 'DEV1'), {
    deviceId: 'DEV1',
    json: '{"user_id":"@a:lowan.local"}',
    updatedAt: new Date(),
  })));

await check('can publish your own device identity key', () =>
  assertSucceeds(setDoc(doc(alice, 'users', ALICE, 'cryptoDevices', 'DEV1'), {
    deviceId: 'DEV1',
    json: '{"user_id":"@a:lowan.local"}',
    updatedAt: new Date(),
  })));

// FCM トークンの devices/ と混ぜていないことの確認。あちらは他人から読めてはいけない。
await check('the FCM device list stays unreadable by others', () =>
  assertFails(getDoc(doc(bob, 'users', ALICE, 'devices', 'DEV1'))));

// ワンタイム鍵は、相手が取って消す。サーバの仲介が無いのでそうするしかない。
// 置き場所は端末の下（E2EE KEYS の節を参照）。
await check('a peer can claim a one-time key', async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(
      doc(ctx.firestore(), 'users', ALICE, 'cryptoDevices', 'DEV1', 'oneTimeKeys', 'OTK1'),
      { json: '{}' },
    );
  });
  const key = doc(bob, 'users', ALICE, 'cryptoDevices', 'DEV1', 'oneTimeKeys', 'OTK1');
  await assertSucceeds(getDoc(key));
  await assertSucceeds(deleteDoc(key));
});

await check("cannot plant a one-time key in somebody else's account", () =>
  assertFails(setDoc(
    doc(bob, 'users', ALICE, 'cryptoDevices', 'DEV1', 'oneTimeKeys', 'OTK2'),
    { json: '{}' },
  )));

// to-device は郵便受け。投函はできる、覗くのは本人だけ。
await check('can post an encrypted to-device message to somebody', () =>
  assertSucceeds(setDoc(doc(bob, 'users', ALICE, 'toDevice', 'EV1'), {
    senderId: BOB,
    body: 'ciphertext',
  })));

await check('cannot post as somebody else', () =>
  assertFails(setDoc(doc(bob, 'users', ALICE, 'toDevice', 'EV2'), {
    senderId: ALICE,
    body: 'ciphertext',
  })));

// 送った本人は、自分が投函したものを読み返せる。端末が自分の配達を確認する方法で、
// 自分が書いたもの以外は何も見えない。他人の受信箱の中身は、その人のもの。
await check('the sender can read back the event they posted', () =>
  assertSucceeds(getDoc(doc(bob, 'users', ALICE, 'toDevice', 'EV1'))));

await check('cannot read an event somebody else posted to a mailbox', async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'users', ALICE, 'toDevice', 'EV_OTHER'), {
      senderId: MALLORY,
      body: 'not yours',
    });
  });
  await assertFails(getDoc(doc(bob, 'users', ALICE, 'toDevice', 'EV_OTHER')));
});

await check('can read your own to-device mailbox', () =>
  assertSucceeds(getDoc(doc(alice, 'users', ALICE, 'toDevice', 'EV1'))));

await check('a posted to-device message cannot be rewritten afterwards', () =>
  assertFails(setDoc(doc(bob, 'users', ALICE, 'toDevice', 'EV1'), {
    senderId: BOB,
    body: 'swapped',
  })));

// 鍵配布の本文に本文を積めないこと。上限が無ければここが抜け道になる。
await check('a to-device body has a ceiling', () =>
  assertFails(setDoc(doc(bob, 'users', ALICE, 'toDevice', 'EV3'), {
    senderId: BOB,
    body: 'x'.repeat(65537),
  })));


// 写真の取り消しは、サムネイルも一緒に持っていく必要がある。残すと「取り消しました」と
// 言いながら写真が見えたままになり、取り消しを用意しないより悪い。
await testEnv.withSecurityRulesDisabled(async (context) => {
  const db = context.firestore();
  await setDoc(doc(db, 'conversations', CONVERSATION, 'messages', 'photo1'), photo());
  await setDoc(doc(db, 'conversations', CONVERSATION, 'messages', 'photo2'), photo());
});

await check('the sender can withdraw a photo, and the thumbnail goes with it', () =>
  assertSucceeds(updateDoc(doc(alice, 'conversations', CONVERSATION, 'messages', 'photo1'), {
    retractedAt: new Date(),
    mediaThumb: deleteField(),
  })));

await check('withdrawing cannot leave the thumbnail behind', () =>
  assertFails(updateDoc(doc(alice, 'conversations', CONVERSATION, 'messages', 'photo2'), {
    retractedAt: new Date(),
  })));

await check('somebody else cannot withdraw your photo', () =>
  assertFails(updateDoc(doc(bob, 'conversations', CONVERSATION, 'messages', 'photo2'), {
    retractedAt: new Date(),
    mediaThumb: deleteField(),
  })));

console.log('\nCONVERSATIONS');

await check('a member can query their own conversations', () =>
  assertSucceeds(getDocs(query(
    collection(alice, 'conversations'),
    where('memberIds', 'array-contains', ALICE),
  ))));

await check('cannot query conversations you are not in', () =>
  assertFails(getDocs(query(
    collection(mallory, 'conversations'),
    where('memberIds', 'array-contains', ALICE),
  ))));

await check('cannot create a conversation you are not a member of', () =>
  assertFails(addDoc(collection(mallory, 'conversations'), {
    type: 'direct',
    memberIds: [ALICE, BOB],
    acceptedBy: [MALLORY],
    createdAt: new Date(),
    updatedAt: new Date(),
    lastMessageAt: new Date(),
  })));

await check('cannot start a conversation with the other side pre-accepted', () =>
  assertFails(addDoc(collection(alice, 'conversations'), {
    type: 'direct',
    memberIds: [ALICE, BOB],
    acceptedBy: [ALICE, BOB],
    createdAt: new Date(),
    updatedAt: new Date(),
    lastMessageAt: new Date(),
  })));

await check('the invited side can accept for itself', () =>
  assertSucceeds(updateDoc(doc(bob, 'conversations', CONVERSATION), {
    acceptedBy: [ALICE, BOB],
  })));

await check('cannot accept on somebody else\'s behalf', () =>
  assertFails(updateDoc(doc(alice, 'conversations', CONVERSATION), {
    acceptedBy: [ALICE, BOB, MALLORY],
  })));

await check('a member cannot add somebody to the conversation', () =>
  assertFails(updateDoc(doc(alice, 'conversations', CONVERSATION), {
    memberIds: [ALICE, BOB, MALLORY],
  })));

await check('a member cannot remove the other member', () =>
  assertFails(updateDoc(doc(alice, 'conversations', CONVERSATION), {
    memberIds: [ALICE],
  })));

await check('a member cannot swap themselves for somebody else', () =>
  assertFails(updateDoc(doc(alice, 'conversations', CONVERSATION), {
    memberIds: [MALLORY, BOB],
  })));

await check('an outsider cannot add themselves', () =>
  assertFails(updateDoc(doc(mallory, 'conversations', CONVERSATION), {
    memberIds: [ALICE, BOB, MALLORY],
  })));

await check('a member can leave by removing only themselves', () =>
  assertSucceeds(updateDoc(doc(bob, 'conversations', LEAVING), {
    memberIds: [ALICE],
    updatedAt: new Date(),
  })));

await check('leaving cannot smuggle in a new member', () =>
  assertFails(updateDoc(doc(bob, 'conversations', LEAVING2), {
    memberIds: [ALICE, MALLORY],
  })));

await check('nobody can delete a conversation', () =>
  assertFails(deleteDoc(doc(alice, 'conversations', CONVERSATION))));

console.log('\nE2EE KEYS');

// 鍵は端末ごとに分けて置く。利用者ごとの1つの束に入れていたときは、2台目が
// 1台目の鍵を上書きし、取得側も端末を指定できなかった。
const otk = (db, user, device, key) =>
  doc(db, 'users', user, 'cryptoDevices', device, 'oneTimeKeys', key);

await check('can publish a one-time key for your own device', () =>
  assertSucceeds(setDoc(otk(alice, ALICE, 'device_a', 'AAAAAQ'), { json: '{"key":"x"}' })));

await check('two devices of one account keep separate keys', () =>
  assertSucceeds(setDoc(otk(alice, ALICE, 'device_b', 'AAAAAQ'), { json: '{"key":"y"}' })));

await check("cannot publish a key into somebody else's device", () =>
  assertFails(setDoc(otk(mallory, ALICE, 'device_a', 'AAAAAg'), { json: '{"key":"z"}' })));

await check('a peer can read a key to start a session', () =>
  assertSucceeds(getDoc(otk(bob, ALICE, 'device_a', 'AAAAAQ'))));

await check('a peer can take a key by deleting it', () =>
  assertSucceeds(deleteDoc(otk(bob, ALICE, 'device_a', 'AAAAAQ'))));

await check('a key cannot carry anything but the bundle', () =>
  assertFails(setDoc(otk(alice, ALICE, 'device_a', 'AAAAAw'), {
    json: '{"key":"x"}',
    stolen: 'extra',
  })));

await check('the old per-account key path is closed', () =>
  assertFails(setDoc(doc(alice, 'users', ALICE, 'oneTimeKeys', 'AAAAAQ'), {
    json: '{"key":"x"}',
  })));

console.log('\nAUDIT — 会話以外の節');

// --- 会話一覧に出る「最後の1行」。中身の検査が無い。
await check('can write your own last-message preview', () =>
  assertSucceeds(updateDoc(doc(bob, 'conversations', CONVERSATION), {
    lastMessage: { preview: '🔒 メッセージ', senderId: BOB, kind: 'enc', sentAt: new Date() },
    lastMessageAt: new Date(),
    updatedAt: new Date(),
  })));

await check('cannot smuggle extra fields into the last-message preview', () =>
  assertFails(updateDoc(doc(bob, 'conversations', CONVERSATION), {
    lastMessage: { preview: 'x', senderId: BOB, kind: 'enc', sentAt: new Date(), extra: 'y' },
  })));

await check('a last-message preview has a length ceiling', () =>
  assertFails(updateDoc(doc(bob, 'conversations', CONVERSATION), {
    lastMessage: { preview: 'x'.repeat(201), senderId: BOB, kind: 'enc', sentAt: new Date() },
  })));

await check('can still write your own profile photo', () =>
  assertSucceeds(setDoc(doc(alice, 'users', ALICE, 'public', 'photo'), {
    bytes: Bytes.fromUint8Array(new Uint8Array(32)),
    updatedAt: new Date(),
  })));

await check('cannot forge the last-message preview', () =>
  assertFails(updateDoc(doc(bob, 'conversations', CONVERSATION), {
    lastMessage: { preview: '今日は休みます', senderId: ALICE, kind: 'text' },
    lastMessageAt: new Date(),
  })));

// --- 招待コード。プロフィールの欄と、コードの台帳が別々に存在する。
await check("can put somebody else's invite code in your own profile（既知・下記）", () =>
  assertSucceeds(setDoc(doc(mallory, 'users', MALLORY), {
    displayName: 'Mallory',
    inviteCode: 'BBBB2222',
    createdAt: new Date(),
    updatedAt: new Date(),
  })));

await check('cannot take over an invite code that is already taken', () =>
  assertFails(setDoc(doc(mallory, 'inviteCodes', 'BBBB2222'), { uid: MALLORY })));

// --- 公開プロフィール。写真の大きさは見ているが、項目は見ていない。
await check('cannot stuff arbitrary fields into your public profile', () =>
  assertFails(setDoc(doc(alice, 'users', ALICE, 'public', 'card'), {
    bytes: Bytes.fromUint8Array(new Uint8Array(16)),
    smuggled: 'x'.repeat(100000),
  })));

// --- 写真の本体。ID は内容の SHA-256 のはずだが、規則は照合していない。
await check('cannot store bytes under an id that is not their hash', () =>
  assertFails(setDoc(doc(mallory, 'media', 'f'.repeat(64)), {
    bytes: Bytes.fromUint8Array(new Uint8Array([1, 2, 3])),
    byteCount: 3,
  })));

// --- スタンプ。所有者以外が消せてはいけない。
await check("cannot delete somebody else's sticker", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'stickers', 'stk1'), {
      ownerId: ALICE,
      bytes: Bytes.fromUint8Array(new Uint8Array(8)),
      contentHash: 'h',
      widthPx: 64,
      heightPx: 64,
    });
  });
  await assertFails(deleteDoc(doc(mallory, 'stickers', 'stk1')));
});

// --- 投票。1人1件を、他人の名前で入れられないこと。
await check('cannot vote as somebody else', async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'feedback', 'fb1'), {
      authorId: ALICE,
      title: 't',
      body: 'b',
      category: 'bug',
    });
  });
  await assertFails(setDoc(doc(mallory, 'feedback', 'fb1', 'votes', ALICE), { at: new Date() }));
});

// --- to-device の受信箱。差出人は自分の投函だけ読み返せる約束になっている。
await check("cannot read somebody else's mailbox entry", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'users', ALICE, 'toDevice', 'ev9'), {
      senderId: BOB,
      body: 'ciphertext',
    });
  });
  await assertFails(getDoc(doc(mallory, 'users', ALICE, 'toDevice', 'ev9')));
});

console.log('\nDISCOVERY');

await check('cannot list the users collection', () =>
  assertFails(getDocs(collection(alice, 'users'))));

await check('cannot list invite codes', () =>
  assertFails(getDocs(collection(alice, 'inviteCodes'))));

await check('can resolve a known invite code', () =>
  assertSucceeds(getDoc(doc(alice, 'inviteCodes', 'BBBB2222'))));

await check("cannot edit somebody else's profile", () =>
  assertFails(updateDoc(doc(mallory, 'users', ALICE), { displayName: 'hacked' })));

await check("cannot read somebody else's settings", () =>
  assertFails(getDoc(doc(mallory, 'users', ALICE, 'settings', 'app'))));

console.log('\nREAD STATE');

await check('cannot mark a message read on behalf of another member', () =>
  assertFails(setDoc(doc(bob, 'conversations', CONVERSATION, 'reads', ALICE), {
    lastReadAt: new Date(),
  })));

await check('can mark your own read position', () =>
  assertSucceeds(setDoc(doc(bob, 'conversations', CONVERSATION, 'reads', BOB), {
    lastReadAt: new Date(),
  })));

console.log('\nFEEDBACK');

await check('can submit feedback as yourself', () =>
  assertSucceeds(addDoc(collection(alice, 'feedback'), {
    authorId: ALICE,
    title: 'スワイプが気持ちいい',
    body: '',
    category: 'ui',
    createdAt: new Date(),
  })));

await check('cannot submit feedback as somebody else', () =>
  assertFails(addDoc(collection(alice, 'feedback'), {
    authorId: BOB,
    title: 'forged',
    body: '',
    category: 'ui',
    createdAt: new Date(),
  })));

await testEnv.cleanup();

console.log(`\n${passed} passed, ${failed} failed\n`);
process.exit(failed === 0 ? 0 : 1);
