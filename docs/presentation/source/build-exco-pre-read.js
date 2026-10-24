// Builds the EXCO two-slide pre-read as Word + PDF from one content model.
// Run: NODE_PATH=<dir containing docx> node build-exco-pre-read.js
// PDF is printed from HTML by headless Chrome (no LibreOffice needed).
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell, WidthType, ShadingType, BorderStyle,
  AlignmentType, LevelFormat, Footer, Header, PageNumber, ImageRun, PageBreak, VerticalAlign,
} = require('docx');

const DOCS = path.resolve(__dirname, '../..');
const ASSETS = path.resolve(__dirname, '../assets');
const MASCOT = '/Users/praveenkumarthangaraj/Documents/CobaltIngestion/voice-insights-ui/public/aia-voice-insights-logo.png';
const WORDMARK = ASSETS + '/aia-voice-insights-wordmark-red.png';
const OUT_BASE = DOCS + '/EXCO-Pre-Read-Business-Value';

const RED = 'D31145', DEEP = '7A0A2B', INK = '1D1D1F', GREY = '6E6E73', MIST = 'F5F5F7', BLUSH = 'FCE8EE', LINE = 'D9D9DE';
const FONT = 'Arial';

// ───────────────────────────── content ─────────────────────────────
// inline: **bold**.  blocks: h1 h2 p bullets numbered table callout quote note pagebreak slidehead
const C = [
  { t: 'p', s: 'One idea sits behind both slides. Every customer conversation holds what decides whether a family is protected well: who depends on whom, what worries them, what they can afford. Today some agents record it on paper, others key it into i-PoS, our point-of-sale system. Either way, **they type it all from memory, after the meeting**, so the record is late, uneven and only as good as what the agent can recall.' },
  { t: 'p', s: '**Voice Insights is a next-generation system: it turns the conversation itself into structured insight and ready-to-review documents within about half a minute**, in English, Mandarin, Malay or Tamil. Agents check and correct instead of typing from memory, and spend their time with customers instead of with forms. AIA Singapore gains an advice process that is consistent, visible and better documented across the whole agency force.' },
  { t: 'h2', s: 'The two slides at a glance' },
  { t: 'table', w: [1700, 3900, 4038], head: ['', 'Slide 1 · Our agents', 'Slide 2 · AIA Singapore'], rows: [
    ['Message', 'Agents get their evenings back, and customers get a better conversation.', 'A consistent, visible, compliant advice process across every agent, at well under one dollar a conversation.'],
    ['Core gains', 'Time back · better conversations · fewer missed needs · safer advice · every customer in their own language', 'Productivity · quality · early compliance · revenue upside · leadership visibility · brand trust'],
  ] },
  { t: 'h2', s: 'What makes it next generation' },
  { t: 'p', s: 'Paper and point-of-sale entry record what an agent remembers. Voice Insights is built to make the agent’s work simpler at every step, with innovations that no form can offer:' },
  { t: 'table', w: [2700, 6938], head: ['Innovation', 'What it does for the agent'], rows: [
    ['Listens, so nobody types', 'Captures the real conversation, including mixed English and Mandarin, in four languages'],
    ['A live picture of the customer', 'Family, hopes and worries build on screen as the customer speaks, each tied to their own words'],
    ['A team of specialist AI reviewers', 'Needs, risk and affordability are assessed together in seconds, with the evidence shown'],
    ['A voice-first debrief partner', 'Talk through the meeting; it reads back what it understood and asks only about what is missing'],
    ['Ask, explore, rehearse', 'Question the analysis by voice, try “what if…?” changes, and practise the next meeting with a coach'],
    ['Paperwork drafted, hands-free', 'Fact-find, record of advice, follow-up and CRM note arrive as drafts; reports can be read aloud on the move'],
  ] },
  { t: 'h2', s: 'What we ask the Committee to take away' },
  { t: 'numbered', items: [
    '**Agents win first.** If it saves them time and makes them better in the room, adoption follows, and company value follows adoption.',
    '**The company benefit is broader than time saved.** Quality, compliance, conversion, visibility and brand are where the larger value lies.',
    '**The numbers must be AIA’s own.** The illustrations show how value is built; measurement turns them into a business case.',
  ] },
  { t: 'pagebreak' },

  // ── Slide 1 ──
  { t: 'slidehead', n: 'SLIDE 1', title: 'What our insurance agents gain', line: '“Agents get their evenings back, and customers get a better conversation.”' },
  { t: 'h2', s: 'The agent’s week, before and after' },
  { t: 'table', w: [2000, 3819, 3819], head: ['', 'Today', 'With Voice Insights'], rows: [
    ['After the meeting', 'Paper notes, or typing into i-PoS field by field, entirely from memory: notes, fact-find, record of advice, follow-up message, CRM entry', 'Drafts of all five are ready to review; the agent checks and corrects instead of re-typing'],
    ['What was said', 'Rebuilt from memory onto paper or i-PoS; a child’s age or a budget can be lost', 'Captured from the conversation, with the customer’s own words kept as evidence'],
    ['What was missed', 'Found at the next meeting, or never, because forms record answers, not gaps', 'Listed straight away as “still to ask”, so the next conversation starts with the right questions'],
    ['Customer follow-up', 'Days later, in whatever time is left', 'The same day, in the customer’s preferred language'],
    ['Confidence in advice', 'Rests on experience and recall', 'Every suggestion shows its reasons, concerns and the product wording behind it'],
  ] },
  { t: 'h2', s: 'Five gains, in the agent’s own terms' },
  { t: 'numbered', items: [
    '**More time to sell and to serve.** Re-typing from memory, onto paper or into i-PoS, shrinks from a long evening task to a short review. The time goes to more customer meetings, or to a better working week.',
    '**Better conversations in the room.** A live view of the customer’s family, hopes and worries builds as they speak, and the next best question is suggested. Newer agents gain a senior colleague’s instinct.',
    '**Fewer missed needs, fewer missed opportunities.** The same structured view is built for every customer, surfacing gaps an agent might not raise: a spouse with no cover, children’s education, a home loan.',
    '**Safer, more confident advice.** A statement that could breach conduct rules, such as a promise of returns, is flagged as it is made, not weeks later in a file review. Agents protect their licence and reputation.',
    '**Every customer in their own language.** Brief and follow up in the language the customer is comfortable with, without extra effort, in a market where family decisions are often made in Mandarin, Malay or Tamil.',
  ] },
  { t: 'h2', s: 'A simpler way of working, not another system to feed' },
  { t: 'p', s: 'Voice Insights is a next-generation tool built around how agents actually work. The agent talks; the system listens, structures and drafts. Voice replaces typing, a live customer picture replaces remembering, and prompts replace guessing what to ask next.' },
  { t: 'h2', s: 'What it means for income and career' },
  { t: 'table', w: [2000, 7638], head: ['', 'Effect'], rows: [
    ['Productivity', 'More customer-facing time each week from the same working hours'],
    ['Quality', 'A fuller record of each customer, supporting stronger, longer relationships and better persistency'],
    ['Growth', 'New agents reach competence faster, because structure and prompts are always at hand'],
    ['Retention', 'A role less weighed down by paperwork is a better reason to stay'],
  ] },
  { t: 'quote', s: 'An agent finishes a meeting and, within about half a minute, has a full suggestion with reasons and a follow-up draft to review.', by: 'Observed in the working prototype' },
  { t: 'note', s: 'Suggested visual: one agent’s week before and after, with the same hours, the admin block shrunk and the customer-time block widened; beside it, one product screen showing the customer’s picture building during a conversation.' },
  { t: 'pagebreak' },

  // ── Slide 2 ──
  { t: 'slidehead', n: 'SLIDE 2', title: 'What AIA Singapore gains', line: '“A consistent, visible, compliant advice process across every agent, at well under one dollar a conversation.”' },
  { t: 'h2', s: 'Six gains for the company' },
  { t: 'table', w: [2700, 6938], head: ['Gain', 'What changes for AIA'], rows: [
    ['1 · Agent productivity at scale', 'Hours returned to customer work across the whole agency force, without adding headcount'],
    ['2 · Quality and consistency', 'Every conversation is analysed to the same standard, so advice quality no longer depends on which agent a customer meets'],
    ['3 · Compliance caught early', 'Risky statements and missing disclosures are visible during or straight after the conversation, not in a later file review; evidence for each suggestion is kept'],
    ['4 · Revenue and conversion upside', 'Faster follow-up, fewer missed needs and better-prepared next meetings lift conversion and protection per customer'],
    ['5 · Leadership visibility', 'A live view of how many conversations happen, which needs come up, how customers feel, and how long it takes to go from meeting to advice'],
    ['6 · Brand and customer trust', 'A fuller, more accurate record and faster multilingual service strengthen AIA as the advisor customers choose'],
  ] },
  { t: 'h2', s: 'The financial logic, kept simple' },
  { t: 'callout', title: 'Value = agents using it × cases per agent × admin time saved per case × value of an hour', body: 'Plus the uplift from better conversion and fewer compliance incidents.' },
  { t: 'table', w: [5338, 4300], head: ['Illustration', 'Example value'], rows: [
    ['Admin time saved per case', '45 minutes'],
    ['Cases per agent per month', '8'],
    ['**Time returned per agent per month**', '**6 hours**'],
    ['Per 100 agents', '600 hours a month, about 3.75 full-time equivalents'],
  ] },
  { t: 'note', s: 'Read this as an illustration, not a forecast. The 45 minutes and 8 cases are planning assumptions. The first step is to measure how long agents really spend today, so every figure shown to the Committee rests on AIA’s own numbers.' },
  { t: 'bullets', items: [
    '**Running cost:** estimated at about SGD 0.35–0.60 per conversation in AI usage, to be confirmed against AIA’s agreements, set against the value of the agent time returned.',
    '**Value beyond time:** a single avoided conduct incident, or a small lift in conversion, may outweigh the time saving.',
  ] },
  { t: 'h2', s: 'Strategic position' },
  { t: 'bullets', items: [
    '**Differentiation:** a faster, multilingual, better-documented agency force is hard for others to copy quickly.',
    '**Next generation, not another form:** voice-first capture, live insight and drafted paperwork replace manual entry from memory, whether on paper or in i-PoS.',
    '**A platform, not a point tool:** the same conversation intelligence can later support coaching, product feedback and new-agent onboarding.',
    '**Insight from the market:** anonymised patterns in what customers ask and worry about show where product and campaign opportunities lie.',
  ] },
  { t: 'quote', s: 'Leadership sees the quality and risk of every conversation in one view, instead of one sampled file at a time.', by: 'The change for leadership' },
  { t: 'pagebreak' },

  // ── Q&A ──
  { t: 'h1', s: 'FAQ' },
  { t: 'table', w: [3000, 6638], head: ['Question', 'Short answer'], rows: [
    ['Will agents actually use it?', 'The value to them is immediate: less typing and better customer conversations. Adoption is the first thing to measure.'],
    ['How is this different from i-PoS or paper?', 'Those record what the agent types from memory after the meeting. Voice Insights captures the conversation itself and drafts the records, so the agent reviews instead of re-typing.'],
    ['Is the saving real, or assumed?', 'Assumed in the illustration above. It is to be measured with a sample of agents before any commitment is made.'],
    ['What does it do for compliance, honestly?', 'It brings risk to light earlier and keeps the evidence behind each suggestion. It supports the compliance function; it does not replace it.'],
    ['Does it help revenue, or only cost?', 'Both. Time returned to selling and fewer missed needs are revenue levers; compliance and rework are cost levers.'],
    ['What is the downside of waiting?', 'Agent time keeps going to paperwork, follow-up stays slow and uneven, and risk is still found late. The value above is deferred, not lost.'],
  ] },
  { t: 'note', s: 'Figures marked as illustrations, estimates or assumptions are planning inputs to be confirmed with Finance, Compliance and a baseline measurement. Product behaviour described here was observed in the current working prototype.' },
];

const TITLE = 'The business case in two slides';
const KICKER = 'Pre-read for the Executive Committee';
const READERS = '5-minute read';

// ───────────────────────────── helpers ─────────────────────────────
const inline = (s) => s.split(/(\*\*[^*]+\*\*)/g).filter(Boolean).map((p) => (p.startsWith('**') ? { b: true, s: p.slice(2, -2) } : { b: false, s: p }));
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const htmlInline = (s) => inline(s).map((p) => (p.b ? `<b>${esc(p.s)}</b>` : esc(p.s))).join('');

// ───────────────────────────── DOCX ─────────────────────────────
const run = (t, o = {}) => new TextRun(Object.assign({ text: t, font: FONT, size: 19, color: INK }, o));
const runs = (s, o = {}) => inline(s).map((p) => run(p.s, Object.assign({}, o, p.b ? { bold: true } : {})));
const CW = 9638;
const ln = { style: BorderStyle.SINGLE, size: 4, color: LINE };
const borders = { top: ln, bottom: ln, left: ln, right: ln };
const none = { style: BorderStyle.NONE, size: 0, color: 'FFFFFF' };
const noBorders = { top: none, bottom: none, left: none, right: none };

function dCell(content, w, o = {}) {
  return new TableCell({
    width: { size: w, type: WidthType.DXA }, borders: o.borders || borders, verticalAlign: VerticalAlign.CENTER,
    shading: o.fill ? { fill: o.fill, type: ShadingType.CLEAR, color: 'auto' } : undefined,
    margins: { top: 50, bottom: 50, left: 110, right: 110 },
    children: [new Paragraph({ spacing: { after: 10, line: 252 }, children: runs(content, Object.assign({ size: 17 }, o.run || {})) })],
  });
}
function dTable(b) {
  return new Table({
    width: { size: CW, type: WidthType.DXA }, columnWidths: b.w,
    rows: [
      new TableRow({ tableHeader: true, children: b.head.map((h, i) => dCell(h, b.w[i], { fill: RED, run: { bold: true, color: 'FFFFFF' } })) }),
      ...b.rows.map((r, ri) => new TableRow({ cantSplit: true, children: r.map((c, i) => dCell(c, b.w[i], { fill: ri % 2 ? MIST : undefined, run: i === 0 ? { bold: true } : {} })) })),
    ],
  });
}
const sp = (n = 100) => new Paragraph({ spacing: { after: n }, children: [] });
const bar = (kids, o = {}) => new Table({
  width: { size: CW, type: WidthType.DXA }, columnWidths: [CW],
  rows: [new TableRow({ children: [new TableCell({
    width: { size: CW, type: WidthType.DXA },
    borders: Object.assign({}, noBorders, o.leftRed ? { left: { style: BorderStyle.SINGLE, size: 24, color: RED } } : {}),
    shading: { fill: o.fill || MIST, type: ShadingType.CLEAR, color: 'auto' },
    margins: { top: 90, bottom: 90, left: 240, right: 240 }, children: kids,
  })] })],
});

function dBlocks() {
  const out = []; let numN = 0;
  // cover block
  out.push(new Table({
    width: { size: CW, type: WidthType.DXA }, columnWidths: [1500, CW - 1500],
    rows: [new TableRow({ children: [
      new TableCell({ width: { size: 1500, type: WidthType.DXA }, borders: noBorders, verticalAlign: VerticalAlign.CENTER, margins: { left: 0, right: 100 },
        children: [new Paragraph({ children: [new ImageRun({ type: 'png', data: fs.readFileSync(MASCOT), transformation: { width: 82, height: 82 }, altText: { title: 'AIA Voice Insights', description: 'AIA Voice Insights mascot', name: 'mascot' } })] })] }),
      new TableCell({ width: { size: CW - 1500, type: WidthType.DXA }, borders: noBorders, verticalAlign: VerticalAlign.CENTER,
        children: [
          new Paragraph({ spacing: { after: 100 }, children: [new ImageRun({ type: 'png', data: fs.readFileSync(WORDMARK), transformation: { width: 300, height: 24.9 }, altText: { title: 'AIA Voice Insights', description: 'AIA Voice Insights wordmark', name: 'wordmark' } })] }),
          new Paragraph({ children: [run('From customer conversation to agent action', { size: 17, color: GREY, bold: true })] }),
        ] }),
    ] })],
  }));
  out.push(new Paragraph({ spacing: { before: 120, after: 160 }, border: { bottom: { style: BorderStyle.SINGLE, size: 18, color: RED, space: 1 } }, children: [] }));
  out.push(new Paragraph({ spacing: { after: 40 }, children: [run(KICKER.toUpperCase(), { size: 17, bold: true, color: RED })] }));
  out.push(new Paragraph({ spacing: { after: 60 }, children: [run(TITLE, { size: 48, bold: true })] }));
  out.push(new Paragraph({ spacing: { after: 200 }, children: [run(READERS, { size: 19, color: GREY })] }));

  for (const b of C) {
    if (b.t === 'p') out.push(new Paragraph({ spacing: { after: 100, line: 270 }, children: runs(b.s, { size: 20 }) }));
    else if (b.t === 'h1') out.push(new Paragraph({ spacing: { before: 120, after: 160 }, keepNext: true, children: [run(b.s, { size: 32, bold: true, color: DEEP })] }));
    else if (b.t === 'h2') out.push(new Paragraph({ spacing: { before: 160, after: 80 }, keepNext: true, border: { left: { style: BorderStyle.SINGLE, size: 24, color: RED, space: 8 } }, indent: { left: 140 }, children: [run(b.s, { size: 24, bold: true })] }));
    else if (b.t === 'bullets') b.items.forEach((s) => out.push(new Paragraph({ numbering: { reference: 'bullets', level: 0 }, spacing: { after: 50, line: 262 }, children: runs(s) })));
    else if (b.t === 'numbered') { const ref = 'num' + (numN++); b.items.forEach((s) => out.push(new Paragraph({ numbering: { reference: ref, level: 0 }, spacing: { after: 60, line: 262 }, children: runs(s) }))); }
    else if (b.t === 'table') { out.push(dTable(b)); out.push(sp(60)); }
    else if (b.t === 'callout') { out.push(bar([
      new Paragraph({ spacing: { after: 60 }, children: [run(b.title, { size: 22, bold: true, color: DEEP })] }),
      new Paragraph({ children: [run(b.body, { size: 19, color: GREY })] }),
    ], { fill: BLUSH, leftRed: true })); out.push(sp(120)); }
    else if (b.t === 'quote') { out.push(sp(60)); out.push(bar([
      new Paragraph({ spacing: { after: 60 }, children: [run('“' + b.s + '”', { size: 23, italics: true, color: DEEP })] }),
      new Paragraph({ children: [run(b.by.toUpperCase(), { size: 15, bold: true, color: RED })] }),
    ], { fill: BLUSH, leftRed: true })); out.push(sp(100)); }
    else if (b.t === 'note') out.push(new Paragraph({ spacing: { before: 80, after: 100, line: 262 }, children: [run(b.s, { size: 17, color: GREY, italics: true })] }));
    else if (b.t === 'pagebreak') out.push(new Paragraph({ children: [new PageBreak()] }));
    else if (b.t === 'slidehead') {
      out.push(new Table({
        width: { size: CW, type: WidthType.DXA }, columnWidths: [CW],
        rows: [new TableRow({ children: [new TableCell({
          width: { size: CW, type: WidthType.DXA }, borders: noBorders, shading: { fill: RED, type: ShadingType.CLEAR, color: 'auto' },
          margins: { top: 140, bottom: 140, left: 300, right: 300 },
          children: [
            new Paragraph({ spacing: { after: 40 }, children: [run(b.n, { size: 16, bold: true, color: 'FFC9D6' })] }),
            new Paragraph({ spacing: { after: 80 }, children: [run(b.title, { size: 34, bold: true, color: 'FFFFFF' })] }),
            new Paragraph({ children: [run(b.line, { size: 21, italics: true, color: 'FFFFFF' })] }),
          ],
        })] })],
      }));
      out.push(sp(40));
    }
  }
  return out;
}

const numCfg = [];
for (let i = 0; i < 10; i++) numCfg.push({ reference: 'num' + i, levels: [{ level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 300 } }, run: { bold: true, color: RED, font: FONT } } }] });

const doc = new Document({
  creator: 'AIA Voice Insights', title: 'AIA Voice Insights: ' + TITLE,
  styles: { default: { document: { run: { font: FONT, size: 20 } } } },
  numbering: { config: [{ reference: 'bullets', levels: [{ level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 270 } }, run: { color: RED } } }] }, ...numCfg] },
  sections: [{
    properties: { page: { size: { width: 11906, height: 16838 }, margin: { top: 700, right: 1020, bottom: 800, left: 1020 } } },
    footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.RIGHT, border: { top: { style: BorderStyle.SINGLE, size: 6, color: RED, space: 6 } }, children: [run('AIA Voice Insights · EXCO pre-read · Page ', { size: 16, color: GREY }), new TextRun({ children: [PageNumber.CURRENT], font: FONT, size: 16, color: GREY })] })] }) },
    children: dBlocks(),
  }],
});

// ───────────────────────────── HTML → PDF ─────────────────────────────
const b64 = (f) => 'data:image/png;base64,' + fs.readFileSync(f).toString('base64');
function html() {
  let body = '';
  for (const b of C) {
    if (b.t === 'p') body += `<p>${htmlInline(b.s)}</p>`;
    else if (b.t === 'h1') body += `<h1>${esc(b.s)}</h1>`;
    else if (b.t === 'h2') body += `<h2>${esc(b.s)}</h2>`;
    else if (b.t === 'bullets') body += '<ul>' + b.items.map((s) => `<li>${htmlInline(s)}</li>`).join('') + '</ul>';
    else if (b.t === 'numbered') body += '<ol>' + b.items.map((s) => `<li>${htmlInline(s)}</li>`).join('') + '</ol>';
    else if (b.t === 'table') body += `<table><thead><tr>${b.head.map((h) => `<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${b.rows.map((r) => `<tr>${r.map((c) => `<td>${htmlInline(c)}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
    else if (b.t === 'callout') body += `<div class="callout"><b>${esc(b.title)}</b><span>${esc(b.body)}</span></div>`;
    else if (b.t === 'quote') body += `<blockquote>“${esc(b.s)}”<cite>${esc(b.by)}</cite></blockquote>`;
    else if (b.t === 'note') body += `<p class="note">${esc(b.s)}</p>`;
    else if (b.t === 'pagebreak') body += '<div class="pb"></div>';
    else if (b.t === 'slidehead') body += `<div class="slidehead"><small>${esc(b.n)}</small><h1>${esc(b.title)}</h1><p>${esc(b.line)}</p></div>`;
  }
  return `<!doctype html><html><head><meta charset="utf-8"><title>AIA Voice Insights: ${TITLE}</title><style>
@page{size:A4;margin:12mm 15mm 14mm}
*{box-sizing:border-box}
body{font:9.3pt/1.42 Arial,Helvetica,sans-serif;color:#${INK};margin:0;-webkit-print-color-adjust:exact;print-color-adjust:exact}
.cover{display:flex;align-items:center;gap:16px;padding-bottom:14px;border-bottom:3px solid #${RED}}
.cover img.m{width:62px;height:62px;border-radius:16px}
.cover img.w{width:290px;display:block;margin-bottom:6px}
.cover small{font-weight:700;color:#${GREY};font-size:8.6pt;letter-spacing:.02em}
.kicker{margin:16px 0 2px;font-size:8.6pt;font-weight:700;letter-spacing:.12em;color:#${RED};text-transform:uppercase}
.title{font-size:21pt;font-weight:700;margin:0 0 4px;line-height:1.15}
.meta{color:#${GREY};font-size:9.4pt;margin:0 0 14px}
h1{font-size:16pt;color:#${DEEP};margin:6px 0 10px}
h2{font-size:11pt;margin:12px 0 6px;padding-left:9px;border-left:4px solid #${RED};break-after:avoid}
p{margin:0 0 6px}
ul,ol{margin:0 0 8px;padding-left:20px} li{margin-bottom:3px}
ol li::marker{color:#${RED};font-weight:700} ul li::marker{color:#${RED}}
table{width:100%;border-collapse:collapse;margin:0 0 6px;font-size:8.3pt}
th{background:#${RED};color:#fff;text-align:left;padding:4px 7px;border:1px solid #${RED}}
td{padding:4px 7px;border:1px solid #${LINE};vertical-align:middle} tr{break-inside:avoid}
tbody tr:nth-child(even) td{background:#${MIST}} td:first-child{font-weight:700}
.callout{background:#${BLUSH};border-left:5px solid #${RED};padding:7px 12px;margin:4px 0 8px}
.callout b{display:block;color:#${DEEP};font-size:10.6pt;margin-bottom:3px}.callout span{color:#${GREY};font-size:9.4pt}
blockquote{background:#${BLUSH};border-left:5px solid #${RED};margin:12px 0 8px;padding:8px 14px;font-style:italic;color:#${DEEP};font-size:10pt;break-inside:avoid}
cite{display:block;margin-top:5px;font-style:normal;font-weight:700;font-size:7.4pt;letter-spacing:.1em;color:#${RED};text-transform:uppercase}
.note{font-size:7.8pt;margin:0 0 4px;color:#${GREY};font-style:italic}
.slidehead{background:linear-gradient(135deg,#${DEEP},#${RED});color:#fff;padding:12px 18px;margin-bottom:4px}
.slidehead small{font-weight:700;letter-spacing:.14em;color:#FFC9D6;font-size:8pt}
.slidehead h1{color:#fff;font-size:19pt;margin:2px 0 4px}.slidehead p{margin:0;font-style:italic;font-size:10.6pt}
.pb{break-after:page}
.foot{position:fixed;bottom:-11mm;right:0;font-size:7.6pt;color:#${GREY}}
</style></head><body>
<div class="cover"><img class="m" src="${b64(MASCOT)}"><div><img class="w" src="${b64(WORDMARK)}"><small>From customer conversation to agent action</small></div></div>
<div class="kicker">${esc(KICKER)}</div><div class="title">${esc(TITLE)}</div><div class="meta">${esc(READERS)}</div>
${body}</body></html>`;
}

(async () => {
  const buf = await Packer.toBuffer(doc);
  fs.writeFileSync(OUT_BASE + '.docx', buf);
  const htmlPath = path.join(process.env.TMPDIR || '/tmp', 'exco-pre-read.html');
  fs.writeFileSync(htmlPath, html());
  execFileSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', [
    '--headless=new', '--disable-gpu', '--no-pdf-header-footer', '--print-to-pdf=' + OUT_BASE + '.pdf', 'file://' + htmlPath,
  ], { stdio: 'ignore' });
  console.log('written', OUT_BASE + '.docx', OUT_BASE + '.pdf');
})();
