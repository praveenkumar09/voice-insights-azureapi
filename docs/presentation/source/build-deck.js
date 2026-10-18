const pptxgen = require('pptxgenjs');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');
const sharp = require('sharp');
const fa = require('react-icons/fa');
const NOTES = require('./notes.js');

const SK = '/Users/praveenkumarthangaraj/.claude/skills/synced/7da554aa-1611-4e4e-a846-d0c024d82b02_4ea665a1-1332-4a2d-bdae-951d29078719/pptx';
const { applyTheme } = require(SK + '/scripts/apply_theme.js');

const OUT = '/Users/praveenkumarthangaraj/Documents/CobaltIngestion/voice-insights-azureapi/docs/presentation';
const A = OUT + '/assets/';
const FILE = OUT + '/AIA-Voice-Insights-Board-Deck.pptx';

const THEME = {
  name: 'AIA Voice Insights',
  headFontFace: 'Arial',
  bodyFontFace: 'Arial',
  colors: {
    dk1: '1D1D1F', lt1: 'FFFFFF', dk2: '6E6E73', lt2: 'F5F5F7',
    accent1: 'D31145', accent2: '9C0C34', accent3: '1BAF7A', accent4: 'D68C14', accent5: '7B4FD6', accent6: '3B82C4',
    hlink: 'D31145', folHlink: '9C0C34',
  },
};
const HEX = { ink: '1D1D1F', grey: '6E6E73', mist: 'F5F5F7', red: 'D31145', deep: '9C0C34', teal: '1BAF7A', amber: 'D68C14', white: 'FFFFFF', line: 'E3E3E8' };

async function icon(Comp, color, size = 256) {
  const svg = renderToStaticMarkup(React.createElement(Comp, { color: '#' + color, size: String(size) }));
  const buf = await sharp(Buffer.from(svg)).png().toBuffer();
  return 'image/png;base64,' + buf.toString('base64');
}

(async () => {
  const pres = new pptxgen();
  pres.layout = 'LAYOUT_WIDE'; // 13.333 x 7.5 in
  pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
  pres.title = 'AIA Voice Insights: board briefing';
  pres.author = 'AIA Voice Insights';
  pres.company = 'AIA';
  const C = pres.SchemeColor;
  const W = 13.333, H = 7.5, MX = 0.8, CW = W - 2 * MX;

  // ── icons ──
  const I = {};
  const need = {
    notes: [fa.FaRegStickyNote, HEX.red], fact: [fa.FaClipboardList, HEX.red], record: [fa.FaFileSignature, HEX.red],
    follow: [fa.FaWhatsapp, HEX.red], crm: [fa.FaDatabase, HEX.red],
    capture: [fa.FaMicrophoneAlt, HEX.white], analyse: [fa.FaProjectDiagram, HEX.white], equip: [fa.FaTasks, HEX.white],
    user: [fa.FaUserTie, HEX.white], ban: [fa.FaBan, HEX.white], scale: [fa.FaBalanceScale, HEX.white],
    hand: [fa.FaHandPointer, HEX.white], check: [fa.FaCheckDouble, HEX.white], lock: [fa.FaLock, HEX.white],
  };
  for (const [k, [comp, color]] of Object.entries(need)) I[k] = await icon(comp, color);
  const IR = {}; // red icons on light tiles
  for (const k of ['notes', 'fact', 'record', 'follow', 'crm']) IR[k] = I[k];

  // ── layouts ──
  const wordmarkRed = A + 'aia-voice-insights-wordmark-red.png';
  const wordmarkWhite = A + 'aia-voice-insights-wordmark-white.png';
  const titleOpts = (color) => ({ name: 'title', type: 'title', x: MX, y: 0.55, w: CW, h: 1.35, fontFace: 'Arial', fontSize: 36, bold: true, color, align: 'left', valign: 'top', margin: 0 });
  pres.defineSlideMaster({
    title: 'LIGHT', background: { color: 'FFFFFF' },
    objects: [
      { image: { x: MX, y: 6.95, w: 1.9, h: 0.158, path: wordmarkRed } },
      { placeholder: { options: titleOpts(C.text1), text: '' } },
    ],
    slideNumber: { x: W - MX - 0.6, y: 6.92, w: 0.6, h: 0.25, color: HEX.grey, fontFace: 'Arial', fontSize: 10, align: 'right' },
  });
  pres.defineSlideMaster({
    title: 'DARK', background: { color: HEX.ink },
    objects: [
      { image: { x: MX, y: 6.95, w: 1.9, h: 0.158, path: wordmarkWhite } },
      { placeholder: { options: titleOpts(C.background1), text: '' } },
    ],
    slideNumber: { x: W - MX - 0.6, y: 6.92, w: 0.6, h: 0.25, color: 'A1A1A6', fontFace: 'Arial', fontSize: 10, align: 'right' },
  });
  const splitTitle = Object.assign(titleOpts(C.text1), { w: 4.2, h: 2.4, fontSize: 32 });
  pres.defineSlideMaster({
    title: 'SPLIT', background: { color: 'FFFFFF' },
    objects: [{ image: { x: MX, y: 6.95, w: 1.9, h: 0.158, path: wordmarkRed } }, { placeholder: { options: splitTitle, text: '' } }],
    slideNumber: { x: W - MX - 0.6, y: 6.92, w: 0.6, h: 0.25, color: HEX.grey, fontFace: 'Arial', fontSize: 10, align: 'right' },
  });
  pres.defineSlideMaster({
    title: 'DARKX', background: { color: HEX.ink },
    objects: [{ image: { x: MX, y: 6.95, w: 1.9, h: 0.158, path: wordmarkWhite } }, { placeholder: { options: Object.assign(titleOpts(C.background1), { y: 2.0, w: 6.4, h: 1.9, fontSize: 48 }), text: '' } }],
    slideNumber: { x: W - MX - 0.6, y: 6.92, w: 0.6, h: 0.25, color: 'A1A1A6', fontFace: 'Arial', fontSize: 10, align: 'right' },
  });
  pres.defineSlideMaster({ title: 'HERO', background: { color: 'FFFFFF' }, objects: [{ image: { x: 0, y: 0, w: W, h: H, path: A + 'hero.png' } }] });

  const shadow = () => ({ type: 'outer', color: '000000', blur: 14, offset: 3, angle: 90, opacity: 0.10 });
  const T = (s, text, o) => s.addText(text, Object.assign({ isTextBox: true, fontFace: 'Arial', margin: 0, color: C.text1 }, o));
  const panel = (s, x, y, w, h, o = {}) => s.addShape(pres.ShapeType.roundRect, Object.assign({
    x, y, w, h, fill: { color: C.background2 }, line: { type: 'none' }, rectRadius: 0.1,
  }, o));
  const dot = (s, x, y, d, color) => s.addShape(pres.ShapeType.ellipse, { x, y, w: d, h: d, fill: { color }, line: { type: 'none' } });
  const shot = (s, file, x, y, w, ratio, name) => {
    const h = w / ratio;
    panel(s, x - 0.06, y - 0.06, w + 0.12, h + 0.12, { fill: { color: C.background1 }, shadow: shadow(), rectRadius: 0.08, objectName: name + ' frame' });
    s.addImage({ path: A + file, x, y, w, h, altText: name, objectName: name });
    return h;
  };
  const add = (layout, key, section) => {
    const s = pres.addSlide({ masterName: layout, sectionTitle: section });
    s.addNotes(NOTES[key].notes + '\n\n[Timing: ' + NOTES[key].time + ']');
    return s;
  };

  // ───────────── 1. Title ─────────────
  pres.addSection({ title: 'Opening' });
  let s = add('HERO', 'title', 'Opening');
  T(s, 'Every\nconversation,\nremembered.', { x: 8.35, y: 1.5, w: 4.4, h: 2.3, fontSize: 38, bold: true, valign: 'top', objectName: 'Title' });
  T(s, 'AIA Voice Insights', { x: 8.35, y: 4.0, w: 4.4, h: 0.4, fontSize: 20, bold: true, color: C.accent1, objectName: 'Product' });
  T(s, 'Board briefing\nAIA Global Innovation Conference', { x: 8.35, y: 4.5, w: 4.4, h: 0.7, fontSize: 16, color: C.text2, objectName: 'Occasion' });
  T(s, '[Presenter name], [title]', { x: 8.35, y: 6.45, w: 4.4, h: 0.3, fontSize: 14, color: C.text2, objectName: 'Presenter' });

  // ───────────── 2. Problem ─────────────
  s = add('LIGHT', 'problem', 'Opening');
  s.addText('The meeting ends. The real work begins', { placeholder: 'title' });
  const pain = [
    ['notes', 'Notes', 'Typed up from memory'],
    ['fact', 'Fact-find', '26 fields to complete'],
    ['record', 'Record of advice', 'Written by hand'],
    ['follow', 'Follow-up', 'Often days later'],
    ['crm', 'CRM update', 'More typing'],
  ];
  const pw = 2.17, pg = 0.22;
  pain.forEach(([k, label, sub], i) => {
    const x = MX + i * (pw + pg);
    panel(s, x, 2.45, pw, 2.7, { shadow: shadow(), objectName: label + ' card' });
    dot(s, x + 0.3, 2.8, 0.8, 'FBE3EA');
    s.addImage({ data: IR[k], x: 0.5 + x, y: 3.0, w: 0.4, h: 0.4, altText: label, objectName: label + ' icon' });
    T(s, label, { x: x + 0.3, y: 3.85, w: pw - 0.5, h: 0.65, fontSize: 17, bold: true, valign: 'top', objectName: label + ' label' });
    T(s, sub, { x: x + 0.3, y: 4.5, w: pw - 0.5, h: 0.4, fontSize: 14, color: C.text2, valign: 'top', objectName: label + ' sub' });
  });
  T(s, 'Planning assumption: about 45 minutes of admin per case. The pilot measures the real number.', { x: MX, y: 5.7, w: CW, h: 0.4, fontSize: 16, color: C.text2, italic: true, objectName: 'Assumption' });

  // ───────────── 3. Idea ─────────────
  s = add('LIGHT', 'idea', 'Opening');
  s.addText('What if the conversation was the work?', { placeholder: 'title' });
  const three = [
    ['capture', 'Capture', 'Dictate once, naturally, in English, Mandarin, Malay or Tamil.'],
    ['analyse', 'Analyse', 'Eleven specialist agents build one picture of the customer, with sources.'],
    ['equip', 'Equip', 'Fact-find, record of advice and follow-up are drafted. The advisor signs off.'],
  ];
  const tw = 3.75, tg = 0.24;
  three.forEach(([k, word, line], i) => {
    const x = MX + i * (tw + tg);
    panel(s, x, 2.3, tw, 3.9, { shadow: shadow(), objectName: word + ' card' });
    dot(s, x + 0.4, 2.7, 0.95, HEX.red);
    s.addImage({ data: I[k], x: x + 0.4 + 0.27, y: 2.7 + 0.27, w: 0.41, h: 0.41, altText: word, objectName: word + ' icon' });
    T(s, word, { x: x + 0.4, y: 3.95, w: tw - 0.8, h: 0.6, fontSize: 30, bold: true, color: C.accent1, objectName: word + ' word' });
    T(s, line, { x: x + 0.4, y: 4.65, w: tw - 0.8, h: 1.3, fontSize: 16, valign: 'top', objectName: word + ' text' });
  });

  // ───────────── 4. Juno story ─────────────
  s = add('LIGHT', 'juno', 'Opening');
  s.addText('We took Juno out of the room', { placeholder: 'title' });
  panel(s, MX, 2.1, 5.4, 3.2, { objectName: 'Before panel' });
  T(s, 'Before', { x: MX + 0.4, y: 2.4, w: 4.6, h: 0.4, fontSize: 14, bold: true, color: C.text2, objectName: 'Before label' });
  T(s, [
    { text: 'An AI host spoke to the customer', options: { bullet: true, breakLine: true } },
    { text: 'First demo feedback: with the advisor in the room, customers prefer the advisor', options: { bullet: true } },
  ], { x: MX + 0.4, y: 2.9, w: 4.6, h: 2.4, fontSize: 18, color: C.text2, valign: 'top', paraSpaceAfter: 12, objectName: 'Before text' });
  panel(s, MX + 5.7, 2.1, CW - 5.7, 3.2, { fill: { color: C.background1 }, line: { color: C.accent1, width: 2 }, shadow: shadow(), objectName: 'Now panel' });
  T(s, 'Now', { x: MX + 6.1, y: 2.4, w: 3, h: 0.4, fontSize: 14, bold: true, color: C.accent1, objectName: 'Now label' });
  T(s, [
    { text: 'Juno works for the advisor, after the meeting', options: { bullet: true, breakLine: true } },
    { text: 'It listens, asks at most five short questions, reads back', options: { bullet: true, breakLine: true } },
    { text: 'Only a person can start the analysis', options: { bullet: true } },
  ], { x: MX + 6.1, y: 2.9, w: CW - 6.5 - 1.1, h: 2.5, fontSize: 18, valign: 'top', paraSpaceAfter: 12, objectName: 'Now text' });
  s.addImage({ path: A + 'juno-avatar.png', x: W - MX - 1.35, y: 2.3, w: 0.95, h: 0.95, rounding: true, altText: 'Juno', objectName: 'Juno avatar' });
  T(s, 'We listened to the room. Then we changed the product.', { x: MX, y: 5.65, w: CW, h: 0.5, fontSize: 22, bold: true, objectName: 'Takeaway' });

  // ───────────── 5. Demo divider ─────────────
  pres.addSection({ title: 'Demo' });
  s = add('DARKX', 'demo', 'Demo');
  s.addText('Let me show you', { placeholder: 'title' });
  T(s, 'Six minutes. Live.', { x: MX, y: 4.0, w: 6, h: 0.5, fontSize: 22, color: 'A1A1A6', objectName: 'Duration' });
  const acts = [['1', 'Debrief with Juno', 'Talk. Juno asks only what is missing.'], ['2', 'Sign off', 'The advisor decides. Not the AI.'], ['3', 'One more thing', 'Practise the next meeting.']];
  acts.forEach(([n, a, b], i) => {
    const y = 1.85 + i * 1.6;
    dot(s, 7.3, y, 0.8, HEX.red);
    T(s, n, { x: 7.3, y, w: 0.8, h: 0.8, fontSize: 26, bold: true, color: C.background1, align: 'center', valign: 'middle', objectName: 'Act ' + n + ' number' });
    T(s, a, { x: 8.35, y: y - 0.02, w: 4.2, h: 0.45, fontSize: 24, bold: true, color: C.background1, objectName: 'Act ' + n });
    T(s, b, { x: 8.35, y: y + 0.45, w: 4.2, h: 0.4, fontSize: 16, color: 'A1A1A6', objectName: 'Act ' + n + ' sub' });
  });

  // ───────────── 6. Debrief screen ─────────────
  pres.addSection({ title: 'Demo screens' });
  s = add('SPLIT', 'debrief', 'Demo screens');
  s.addText('Juno listens, then asks only what is missing', { placeholder: 'title' });
  T(s, [
    { text: 'Every fact appears with the words it came from', options: { bullet: true, breakLine: true } },
    { text: 'Juno catches a promise and offers a compliant way to record it', options: { bullet: true, breakLine: true } },
    { text: 'At most five questions. Always skippable', options: { bullet: true } },
  ], { x: MX, y: 3.25, w: 4.0, h: 3.0, fontSize: 16, valign: 'top', paraSpaceAfter: 14, objectName: 'Debrief points' });
  shot(s, 'app-juno-catches-promise.jpg', 5.2, 1.35, 7.45, 1360 / 750, 'Juno debrief screen');

  // ───────────── 7. Sign-off screen ─────────────
  s = add('SPLIT', 'signoff', 'Demo screens');
  s.addText('The advisor signs off. Not the AI', { placeholder: 'title' });
  T(s, [
    { text: 'Names, numbers and promises marked where they were said', options: { bullet: true, breakLine: true } },
    { text: 'One tap to confirm or fix a fact', options: { bullet: true, breakLine: true } },
    { text: 'One button starts the analysis. Only a person can press it', options: { bullet: true } },
  ], { x: MX, y: 3.25, w: 4.0, h: 3.0, fontSize: 16, valign: 'top', paraSpaceAfter: 14, objectName: 'Sign-off points' });
  shot(s, 'app-signoff.jpg', 5.2, 1.5, 7.45, 1360 / 720, 'Sign-off screen');

  // ───────────── 8. Dress rehearsal ─────────────
  s = add('LIGHT', 'rehearsal', 'Demo screens');
  s.addText('One more thing: practise before it counts', { placeholder: 'title', h: 0.8 });
  const h1 = shot(s, 'app-rehearsal-coach-flag.jpg', MX + 0.06, 2.05, 5.7, 1250 / 538, 'Rehearsal with live coach flag');
  const h2 = shot(s, 'app-coach-notes.jpg', MX + 6.15 + 0.06, 2.05, 5.7, 1250 / 606, 'Coach notes');
  T(s, 'Juno plays the customer. A coach flags a risky promise the moment it is said.', { x: MX, y: 2.05 + Math.max(h1, h2) + 0.25, w: 5.8, h: 0.7, fontSize: 14, color: C.text2, valign: 'top', objectName: 'Caption left' });
  T(s, 'Afterwards: what landed, what was missed, and what to say instead.', { x: MX + 6.15, y: 2.05 + Math.max(h1, h2) + 0.25, w: 5.7, h: 0.7, fontSize: 14, color: C.text2, valign: 'top', objectName: 'Caption right' });
  T(s, 'Training only: a simulation built from the debrief. Nothing is saved or sent.', { x: MX, y: 6.45, w: CW - 2.2, h: 0.3, fontSize: 12, color: C.text2, italic: true, objectName: 'Training note' });

  // ───────────── 9. Trust ─────────────
  pres.addSection({ title: 'Trust and delivery' });
  s = add('LIGHT', 'trust', 'Trust and delivery');
  s.addText('The advisor decides. Always', { placeholder: 'title', h: 0.8 });
  const rules = [
    ['user', 'Juno speaks only to the advisor', 'Never to the customer.'],
    ['ban', 'No prices, no returns, no products', 'It never quotes a figure or pushes a product.'],
    ['scale', '"Suggest", never "recommend"', 'MAS wording. Relevance in words, not percentages.'],
    ['hand', 'Only a person starts the analysis', 'The button ignores scripted clicks.'],
    ['check', 'Every fact is confirmed on screen', 'Names and numbers are checked before use.'],
    ['lock', 'Nothing is sent automatically', 'Drafts only. ID and card numbers masked before storage.'],
  ];
  const rw = 5.75, rh = 1.25;
  rules.forEach(([k, a, b], i) => {
    const col = i % 2, row = Math.floor(i / 2);
    const x = MX + col * (rw + 0.23), y = 1.8 + row * (rh + 0.18);
    panel(s, x, y, rw, rh, { shadow: shadow(), objectName: a + ' card' });
    dot(s, x + 0.3, y + 0.27, 0.72, HEX.red);
    s.addImage({ data: I[k], x: x + 0.3 + 0.19, y: y + 0.27 + 0.19, w: 0.34, h: 0.34, altText: a, objectName: a + ' icon' });
    T(s, a, { x: x + 1.3, y: y + 0.2, w: rw - 1.5, h: 0.4, fontSize: 17, bold: true, objectName: a });
    T(s, b, { x: x + 1.3, y: y + 0.62, w: rw - 1.5, h: 0.55, fontSize: 14, color: C.text2, valign: 'top', objectName: a + ' detail' });
  });
  T(s, 'Controls work in the prototype and are pilot-grade. Compliance, Security and Privacy review is part of the pilot.', { x: MX, y: 6.2, w: CW, h: 0.35, fontSize: 14, color: C.text2, italic: true, objectName: 'Honesty note' });

  // ───────────── 10. Architecture ─────────────
  s = add('LIGHT', 'architecture', 'Trust and delivery');
  s.addText('Standard parts. No exotic bets', { placeholder: 'title', h: 0.8 });
  const box = (x, y, w, h, title, sub, o = {}) => {
    panel(s, x, y, w, h, Object.assign({ shadow: shadow(), objectName: title + ' box' }, o.panel || {}));
    T(s, title, { x: x + 0.25, y: y + 0.2, w: w - 0.5, h: 0.4, fontSize: 18, bold: true, color: o.color || C.text1, objectName: title });
    T(s, sub, { x: x + 0.25, y: y + 0.65, w: w - 0.5, h: h - 0.8, fontSize: 14, color: o.sub || C.text2, valign: 'top', objectName: title + ' detail' });
  };
  box(MX, 2.5, 2.9, 1.9, "Advisor's browser", 'React 18 and TypeScript. Voice in, words out.');
  box(4.85, 2.1, 3.7, 2.7, 'Voice Insights API', 'Java 21, Spring Boot, Spring AI.\n11 specialist agents (LangGraph4j), three in parallel. Every step stored for audit.', { panel: { fill: { color: C.accent1 }, shadow: shadow() }, color: C.background1, sub: 'FFFFFF' });
  box(9.4, 1.85, 3.13, 1.15, 'Azure OpenAI', 'Speech, reasoning, voice');
  box(9.4, 3.15, 3.13, 1.15, 'PostgreSQL', 'pgvector search, profiles, documents');
  box(9.4, 4.45, 3.13, 1.15, 'Redis', 'Sessions');
  const arrow = (x1, y1, x2, y2) => s.addShape(pres.ShapeType.line, { x: x1, y: y1, w: x2 - x1, h: y2 - y1, line: { color: C.text2, width: 1.75, endArrowType: 'triangle' } });
  arrow(3.7, 3.45, 4.85, 3.45);
  arrow(8.55, 3.45, 9.4, 3.45);
  T(s, 'Containers on Azure Container Apps or AKS. Scales by adding capacity, not by redesign.', { x: MX, y: 5.95, w: CW - 2.2, h: 0.5, fontSize: 16, color: C.text2, objectName: 'Delivery note' });

  // ───────────── 11. Targets ─────────────
  pres.addSection({ title: 'Value and decision' });
  s = add('LIGHT', 'targets', 'Value and decision');
  s.addText('How we will know it works', { placeholder: 'title', h: 0.8 });
  const stats = [
    ['≥ 50%', 'less time from meeting to documented advice'],
    ['90%', 'of follow-ups sent within 24 hours'],
    ['≥ 80%', 'fact-find completeness'],
    ['100%', 'of advice packs signed off by an advisor'],
    ['≥ 4 / 5', 'advisor satisfaction'],
    ['< SGD 1', 'to run one conversation'],
  ];
  const sw = 3.75, sg = 0.24, sh = 1.95;
  stats.forEach(([n, l], i) => {
    const col = i % 3, row = Math.floor(i / 3);
    const x = MX + col * (sw + sg), y = 1.85 + row * (sh + 0.22);
    panel(s, x, y, sw, sh, { shadow: shadow(), objectName: l + ' tile' });
    T(s, n, { x: x + 0.35, y: y + 0.25, w: sw - 0.7, h: 0.85, fontSize: 44, bold: true, color: C.accent1, objectName: 'Target ' + n });
    T(s, l, { x: x + 0.35, y: y + 1.15, w: sw - 0.7, h: 0.65, fontSize: 15, valign: 'top', objectName: 'Target label ' + n });
  });
  T(s, 'Proposed pilot targets, to be agreed. The baseline is measured first, with the advisors who take part.', { x: MX, y: 6.15, w: CW - 2.2, h: 0.5, fontSize: 14, color: C.text2, italic: true, objectName: 'Targets note' });

  // ───────────── 12. Numbers ─────────────
  s = add('LIGHT', 'numbers', 'Value and decision');
  s.addText('The numbers, honestly', { placeholder: 'title', h: 0.8 });
  panel(s, MX, 1.85, 6.0, 4.35, { shadow: shadow(), objectName: 'Value panel' });
  T(s, 'If it works at scale (illustration)', { x: MX + 0.4, y: 2.1, w: 5.2, h: 0.4, fontSize: 14, bold: true, color: C.text2, objectName: 'Value label' });
  T(s, '200 advisors × 8 cases a month × 45 minutes', { x: MX + 0.4, y: 2.6, w: 5.2, h: 0.8, fontSize: 18, valign: 'top', objectName: 'Value inputs' });
  T(s, '1,200', { x: MX + 0.4, y: 3.45, w: 5.2, h: 1.1, fontSize: 66, bold: true, color: C.accent1, objectName: 'Value number' });
  T(s, 'hours a month, about 7.5 full-time equivalents back with customers', { x: MX + 0.4, y: 4.6, w: 5.2, h: 0.8, fontSize: 16, valign: 'top', objectName: 'Value caption' });
  T(s, 'An example, not a forecast. The pilot replaces each input with a measured one.', { x: MX + 0.4, y: 5.5, w: 5.2, h: 0.55, fontSize: 13, color: C.text2, italic: true, valign: 'top', objectName: 'Value note' });
  panel(s, MX + 6.25, 1.85, CW - 6.25, 4.35, { shadow: shadow(), objectName: 'Cost panel' });
  T(s, 'What it costs', { x: MX + 6.65, y: 2.1, w: 4, h: 0.4, fontSize: 14, bold: true, color: C.text2, objectName: 'Cost label' });
  const costs = [['SGD 0.35 – 0.60', 'to run one conversation (estimate)'], ['SGD 240 – 350k', 'four-month proof-of-concept planning range, mostly team time'], ['SGD 34 – 74k', 'extra cash if the team is existing AIA staff']];
  costs.forEach(([n, l], i) => {
    const y = 2.6 + i * 1.18;
    T(s, n, { x: MX + 6.65, y, w: CW - 7.05, h: 0.55, fontSize: 28, bold: true, objectName: 'Cost ' + n });
    T(s, l, { x: MX + 6.65, y: y + 0.55, w: CW - 7.05, h: 0.5, fontSize: 14, color: C.text2, valign: 'top', objectName: 'Cost label ' + n });
  });
  T(s, 'Planning figures for Finance to confirm. Estimates use public list prices and move with AIA\'s Azure agreement.', { x: MX, y: 6.4, w: CW - 2.2, h: 0.45, fontSize: 12, color: C.text2, italic: true, objectName: 'Numbers note' });

  // ───────────── 13. Ask ─────────────
  s = add('LIGHT', 'ask', 'Value and decision');
  s.addText('Three decisions we need from you', { placeholder: 'title', h: 0.8 });
  const asks = [
    ['1', 'Approve a controlled pilot', '10 to 20 advisors, measured against a baseline'],
    ['2', 'Name the owners', 'Compliance, Legal, Security and Data Privacy'],
    ['3', 'Confirm the environment', 'The Azure region, and release the advisor group'],
  ];
  asks.forEach(([n, a, b], i) => {
    const y = 1.85 + i * 1.18;
    dot(s, MX, y, 0.85, HEX.red);
    T(s, n, { x: MX, y, w: 0.85, h: 0.85, fontSize: 28, bold: true, color: C.background1, align: 'center', valign: 'middle', objectName: 'Decision ' + n + ' number' });
    T(s, a, { x: MX + 1.15, y: y - 0.02, w: 10, h: 0.5, fontSize: 24, bold: true, objectName: 'Decision ' + n });
    T(s, b, { x: MX + 1.15, y: y + 0.48, w: 10, h: 0.4, fontSize: 16, color: C.text2, objectName: 'Decision ' + n + ' detail' });
  });
  const steps = [['Today', 'A working prototype'], ['Pilot', 'Measure, review, harden'], ['Next', 'CRM, more products and markets']];
  const cw3 = 3.75;
  steps.forEach(([a, b], i) => {
    const x = MX + i * (cw3 + 0.24), y = 5.5;
    s.addShape(pres.ShapeType.chevron, { x, y, w: cw3, h: 1.05, fill: { color: i === 0 ? C.accent1 : C.background2 }, line: { type: 'none' }, objectName: a + ' step' });
    T(s, a, { x: x + 0.5, y: y + 0.12, w: cw3 - 1.0, h: 0.35, fontSize: 16, bold: true, color: i === 0 ? C.background1 : C.text1, objectName: a });
    T(s, b, { x: x + 0.5, y: y + 0.5, w: cw3 - 1.0, h: 0.4, fontSize: 13, color: i === 0 ? C.background1 : C.text2, objectName: a + ' detail' });
  });

  // ───────────── 14. Close ─────────────
  s = add('DARKX', 'close', 'Trust and delivery');
  s.addText('The best technology disappears', { placeholder: 'title' });
  T(s, 'The advisor shines.', { x: MX, y: 4.1, w: 7, h: 0.9, fontSize: 40, bold: true, color: C.accent1, objectName: 'Closing line' });
  T(s, 'Capture   ·   Analyse   ·   Equip', { x: MX, y: 5.3, w: 7, h: 0.4, fontSize: 18, color: 'A1A1A6', objectName: 'Tagline' });
  s.addImage({ path: A + 'juno-avatar.png', x: 8.3, y: 1.55, w: 3.9, h: 3.9, rounding: true, altText: 'Juno, the Voice Insights assistant', objectName: 'Juno avatar' });

  // ───────────── Appendix ─────────────
  pres.addSection({ title: 'Appendix' });
  s = add('LIGHT', 'risks', 'Appendix');
  s.addText('Appendix: risks and how they are handled', { placeholder: 'title', h: 0.8, fontSize: 30 });
  const hd = { bold: true, color: C.background1, fill: { color: C.accent1 }, fontFace: 'Arial', fontSize: 14, valign: 'middle' };
  const cell = (t, o = {}) => ({ text: t, options: Object.assign({ fontFace: 'Arial', fontSize: 14, color: C.text1, valign: 'top' }, o) });
  s.addTable([
    [{ text: 'Risk', options: hd }, { text: 'Level', options: hd }, { text: 'How it is handled', options: hd }],
    [cell('Non-compliant output (a price, a guarantee)'), cell('Lower now'), cell('Juno speaks only to the advisor; scripted test pack; Compliance review before any pilot; every output reviewable')],
    [cell('Personal data and consent (PDPA)'), cell('High'), cell('Privacy assessment, retention rules and advisor-side consent practice agreed with Data Privacy first')],
    [cell('Names and accents misheard'), cell('Medium'), cell('Every fact confirmed on screen; native-speaker testing for Mandarin, Malay and Tamil')],
    [cell('Slower than dictating'), cell('Medium'), cell('At most five questions, always skippable; the pilot compares both modes on time and completeness')],
    [cell('Azure capacity or stalls at pilot load'), cell('Medium'), cell('Request quota early; automatic retries and a second attempt on slow calls; fallback voice')],
    [cell('Advisors do not adopt it'), cell('Medium'), cell('Involve advisors from the start; measure time saved with them; the advisor stays the decision-maker')],
  ], { x: MX, y: 1.5, w: CW, colW: [3.6, 1.5, CW - 5.1], rowH: 0.62, border: { type: 'solid', color: HEX.line, pt: 0.75 }, autoPage: false });

  s = add('LIGHT', 'status', 'Appendix');
  s.addText('Appendix: where we are, exactly', { placeholder: 'title', h: 0.8, fontSize: 30 });
  const cols = [
    ['Built and demonstrated', C.accent3, ['Three ways to capture a conversation', 'Juno debrief with the fact-find ledger', 'Sign-off screen and follow-up drafts in four languages', 'Dress Rehearsal', '11-agent analysis and advice pack', 'Admin dashboard']],
    ['Tested so far', C.accent6, ['Scripted spoken debriefs and rehearsals', 'English, synthetic voice', 'A prototype test, not a pilot', 'Replies take a few seconds; slower in a noisy room']],
    ['Not yet done', C.accent4, ['Testing with real advisors', 'Native-speaker review: Mandarin, Malay, Tamil', 'Formal Compliance, Security and privacy review', 'CRM write-back', 'Measured time savings', 'Load testing']],
  ];
  cols.forEach(([h, color, items], i) => {
    const x = MX + i * (3.75 + 0.24);
    panel(s, x, 1.55, 3.75, 4.95, { shadow: shadow(), objectName: h + ' panel' });
    T(s, h, { x: x + 0.3, y: 1.8, w: 3.15, h: 0.45, fontSize: 18, bold: true, color, objectName: h });
    T(s, items.map((t, j) => ({ text: t, options: { bullet: true, breakLine: j < items.length - 1 } })), { x: x + 0.3, y: 2.4, w: 3.15, h: 3.9, fontSize: 14, valign: 'top', paraSpaceAfter: 8, objectName: h + ' list' });
  });

  s = add('LIGHT', 'qa', 'Appendix');
  s.addText('Appendix: likely questions', { placeholder: 'title', h: 0.8, fontSize: 30 });
  const qa = [
    ['Is this "advice" under MAS rules?', 'No. It produces suggestions for the advisor to review; the advisor decides. Compliance should still review it formally.'],
    ['Can it make things up?', 'It is limited to the customer file and our product documents. Statements without a source are marked. A pilot-grade control.'],
    ['What is the business value?', 'Not measured yet. I would not quote a number today. The pilot measures it.'],
    ['Why not a general chatbot?', 'It is not tied to our customer file or product documents, shows no sources, and does not produce our paperwork.'],
  ];
  qa.forEach(([q, a], i) => {
    const col = i % 2, row = Math.floor(i / 2);
    const x = MX + col * 6.0, y = 1.6 + row * 2.45;
    panel(s, x, y, 5.75, 2.25, { shadow: shadow(), objectName: q + ' card' });
    T(s, q, { x: x + 0.3, y: y + 0.25, w: 5.15, h: 0.5, fontSize: 17, bold: true, color: C.accent1, valign: 'top', objectName: q });
    T(s, a, { x: x + 0.3, y: y + 0.85, w: 5.15, h: 1.25, fontSize: 14, valign: 'top', objectName: q + ' answer' });
  });

  await pres.writeFile({ fileName: FILE });
  await applyTheme(FILE, THEME);
  console.log('written', FILE);
})();
