'use strict';
/** Turns results.json into something a person can read. */

const WORKLOAD_NOTES = {
  single: 'one message, sent alone — the common case and the hard one',
  burst: 'six messages, as a reconnect delivers them',
  sync: 'a whole thread — first open, or a new device',
  bundle: 'four conversations in one blob — the Mesh courier shape',
};

function pad(s, n, right) {
  s = String(s);
  return right ? s.padStart(n) : s.padEnd(n);
}

module.exports = function report(results) {
  const meta = results._meta;
  const lines = [];
  lines.push('');
  lines.push(`node ${meta.node} · ${meta.cpu}`);
  lines.push(`trained on ${meta.trainMessages} messages · dict32k=${meta.dict32k}B dict110k=${meta.dict110k}B`);
  lines.push(`ETX model ${(meta.etxModelBytes / 1024).toFixed(1)}KB · KANA8 table ${(meta.kana8TableBytes / 1024).toFixed(1)}KB`);
  lines.push('');

  for (const [wl, rows] of Object.entries(results)) {
    if (wl === '_meta') continue;
    lines.push('');
    lines.push(`### ${wl.toUpperCase()}  (${WORKLOAD_NOTES[wl]})  n=${rows[0].n}`);
    lines.push('');
    lines.push([
      pad('format', 11), pad('codec', 15), pad('B/msg', 9, true), pad('vs JSON', 9, true),
      pad('worse', 7, true), pad('enc us', 9, true), pad('dec us', 9, true), pad('dict', 8, true),
    ].join(' '));
    lines.push('-'.repeat(84));

    const sorted = [...rows].sort((a, b) => a.perMsg - b.perMsg);
    for (const r of sorted) {
      lines.push([
        pad(r.format, 11),
        pad(r.codec, 15),
        pad(r.perMsg.toFixed(1), 9, true),
        pad((r.vsJson * 100).toFixed(1) + '%', 9, true),
        pad(r.worse ? `${((r.worse / r.n) * 100).toFixed(0)}%` : '-', 7, true),
        pad(r.encUsPerItem === null ? '-' : r.encUsPerItem.toFixed(2), 9, true),
        pad(r.decUsPerItem === null ? '-' : r.decUsPerItem.toFixed(2), 9, true),
        pad(r.dict ? (r.dict / 1024).toFixed(0) + 'K' : '-', 8, true),
      ].join(' '));
    }
  }

  lines.push('');
  console.log(lines.join('\n'));
  require('fs').writeFileSync(require('path').join(__dirname, 'results.txt'), lines.join('\n'));
};
