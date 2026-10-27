// Editable PowerPoint of the same story as the video: native text boxes and shapes, speaker notes = the narration.
const pptxgen = require('pptxgenjs'); const path = require('path'); const fs = require('fs');
const D = JSON.parse(fs.readFileSync(path.join(__dirname, 'deck.json'), 'utf8'));
const A = (f) => path.join(__dirname, 'pptx-assets', f);
const X = (px) => px / 144;                        // 1920 px design grid -> 13.333 in
const RED = 'D31145', INK = '1D1D1F', GREY = '55555B', LINE = 'EBD3DA', BLUSH = 'FCE8EE', PINK = 'FFB3C6', FONT = 'Arial';
const p = new pptxgen(); p.layout = 'LAYOUT_WIDE'; p.title = D.title; p.company = 'AIA Voice Insights'; p.author = 'AIA Voice Insights';
const ROW_Y = [300, 448, 596, 744];
// "Every <em>word</em>" -> text runs with the accent colour
function runs(html, base, accent, size) {
  const out = []; const re = /<em>(.*?)<\/em>/g; let last = 0, m;
  while ((m = re.exec(html))) { if (m.index > last) out.push({ text: html.slice(last, m.index), options: { color: base } }); out.push({ text: m[1], options: { color: accent } }); last = re.lastIndex; }
  if (last < html.length) out.push({ text: html.slice(last), options: { color: base } });
  return out.map((r) => ({ text: r.text.replace(/&amp;/g, '&'), options: { ...r.options, fontFace: FONT, fontSize: size, bold: true } }));
}
const MARK_IMG = path.join(__dirname, 'vlogo.png');
function brand(s, dark) {
  s.addImage({ path: MARK_IMG, x: X(1385), y: X(30), w: X(46), h: X(46) });
  s.addText([{ text: 'AIA  ', options: { bold: true, color: dark ? PINK : RED } }, { text: 'VOICE INSIGHTS', options: { color: dark ? 'FFFFFF' : INK } }],
    { x: X(1856) - X(540), y: X(24), w: X(540), h: X(60), align: 'right', fontFace: FONT, fontSize: 15, charSpacing: 3, valign: 'middle' });
}
function head(s, sl, dark) {
  s.addText(sl.kicker, { x: X(96), y: X(86), w: X(1400), h: X(40), fontFace: FONT, fontSize: 15, bold: true, color: dark ? PINK : RED, charSpacing: 6 });
  s.addText(runs(sl.title, dark ? 'FFFFFF' : INK, dark ? PINK : RED, 40), { x: X(96), y: X(124), w: X(1560), h: X(100), valign: 'top' });
}
const ONLY = process.env.ONLY ? process.env.ONLY.split(',') : null;
for (const sl of D.slides.filter((x) => !ONLY || ONLY.includes(x.id))) {
  const s = p.addSlide(); const note = D.beats[sl.beat]; s.addNotes(note);
  const dark = ['stats', 'close'].includes(sl.kind);
  if (sl.kind === 'title') {
    s.background = { path: A('bg-title.png') };
    s.addText(sl.kicker, { x: X(1130), y: X(270), w: X(720), h: X(40), fontFace: FONT, fontSize: 15, bold: true, color: RED, charSpacing: 6 });
    s.addText(runs(sl.title, INK, RED, 54), { x: X(1130), y: X(320), w: X(720), h: X(330), valign: 'top' });
    s.addText(sl.sub, { x: X(1130), y: X(660), w: X(720), h: X(130), fontFace: FONT, fontSize: 20, color: GREY, valign: 'top' });
    continue;
  }
  s.background = { path: A(dark ? 'bg-dark.png' : 'bg-light.png') };
  brand(s, dark);
  if (sl.kind === 'close') {
    s.addImage({ path: path.join(__dirname, 'juno-mascot.png'), x: X(150), y: X(250), w: X(760), h: X(760 * 450 / 580), rounding: false });
    s.addText(sl.kicker, { x: X(1000), y: X(250), w: X(820), h: X(40), fontFace: FONT, fontSize: 15, bold: true, color: PINK, charSpacing: 6 });
    s.addText(runs(sl.title, 'FFFFFF', PINK, 62), { x: X(1000), y: X(300), w: X(820), h: X(300), valign: 'top' });
    s.addText(sl.sub, { x: X(1000), y: X(620), w: X(820), h: X(120), fontFace: FONT, fontSize: 20, color: 'FFE3EA', valign: 'top' });
    continue;
  }
  head(s, sl, dark);
  if (sl.rows) {
    sl.rows.forEach((r, i) => {
      const y = ROW_Y[i];
      s.addShape(p.ShapeType.roundRect, { x: X(96), y: X(y), w: X(1090), h: X(128), rectRadius: 0.18, fill: { color: 'FFFFFF' }, line: { color: LINE, width: 1 } });
      s.addShape(p.ShapeType.roundRect, { x: X(126), y: X(y + 28), w: X(72), h: X(72), rectRadius: 0.14, fill: { color: BLUSH }, line: { color: BLUSH, width: 0 } });
      s.addImage({ path: A(`${r.icon}-red.png`), x: X(142), y: X(y + 44), w: X(40), h: X(40) });
      s.addText([{ text: r.title, options: { bold: true, fontSize: 20, color: INK, breakLine: true } }, { text: r.sub, options: { fontSize: 13, color: GREY } }],
        { x: X(224), y: X(y + 8), w: X(940), h: X(112), fontFace: FONT, valign: 'middle', paraSpaceAfter: 2 });
    });
    s.addImage({ path: A('impact.png'), x: X(1250), y: X(300), w: X(574), h: X(572) });
    s.addImage({ path: A(`${sl.kind === 'problem' ? 'alert' : 'chart'}-white.png`), x: X(1292), y: X(342), w: X(44), h: X(44) });
    s.addText(sl.impact.label, { x: X(1292), y: X(398), w: X(490), h: X(30), fontFace: FONT, fontSize: 12, bold: true, color: PINK, charSpacing: 5 });
    s.addText(sl.impact.big, { x: X(1292), y: X(436), w: X(490), h: X(170), fontFace: FONT, fontSize: 32, bold: true, color: 'FFFFFF', valign: 'top' });
    s.addText(sl.impact.text, { x: X(1292), y: X(626), w: X(490), h: X(210), fontFace: FONT, fontSize: 16, color: 'FFE3EA', valign: 'top' });
  }
  if (sl.kind === 'impact') {
    sl.tiles.forEach((t, i) => {
      const x = 96 + (i % 2) * 824, y = 300 + Math.floor(i / 2) * 296;
      s.addShape(p.ShapeType.roundRect, { x: X(x), y: X(y), w: X(796), h: X(270), rectRadius: 0.2, fill: { color: 'FFFFFF' }, line: { color: LINE, width: 1 } });
      s.addShape(p.ShapeType.roundRect, { x: X(x + 40), y: X(y + 36), w: X(76), h: X(76), rectRadius: 0.15, fill: { color: BLUSH }, line: { color: BLUSH, width: 0 } });
      s.addImage({ path: A(`${t.icon}-red.png`), x: X(x + 58), y: X(y + 54), w: X(40), h: X(40) });
      s.addText(t.who, { x: X(x + 40), y: X(y + 124), w: X(716), h: X(56), fontFace: FONT, fontSize: 26, bold: true, color: RED });
      s.addText(t.text, { x: X(x + 40), y: X(y + 180), w: X(716), h: X(70), fontFace: FONT, fontSize: 16, color: '3A3A3F', valign: 'top' });
    });
  }
  if (sl.kind === 'stats') {
    sl.tiles.forEach((t, i) => {
      const x = 96 + i * 592;
      s.addShape(p.ShapeType.roundRect, { x: X(x), y: X(300), w: X(560), h: X(500), rectRadius: 0.22, fill: { color: 'FFFFFF', transparency: 88 }, line: { color: 'FFFFFF', width: 1, transparency: 70 } });
      s.addText(t.v, { x: X(x + 44), y: X(344), w: X(480), h: X(150), fontFace: FONT, fontSize: 64, bold: true, color: 'FFFFFF', valign: 'top' });
      s.addText(t.text, { x: X(x + 44), y: X(520), w: X(480), h: X(240), fontFace: FONT, fontSize: 17, color: 'FFE3EA', valign: 'top' });
    });
    s.addText('Sources: LIA 2025 and H1 2026 results; LIA 2022 protection gap study. The 74,000 hours figure is an illustration, to be proven with AIA\'s own numbers.',
      { x: X(96), y: X(830), w: X(1700), h: X(40), fontFace: FONT, fontSize: 11, color: 'E9B9C7' });
  }
}
p.writeFile({ fileName: process.env.OUT || path.join(__dirname, '..', 'AIA-Voice-Insights-Product-Overview.pptx') }).then(() => console.log('pptx ok'));
