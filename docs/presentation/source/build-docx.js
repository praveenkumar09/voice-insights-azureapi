const fs = require('fs');
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell, WidthType, ShadingType, BorderStyle,
  AlignmentType, HeadingLevel, LevelFormat, Header, Footer, PageNumber, ImageRun,
} = require('docx');

const OUT = '/Users/praveenkumarthangaraj/Documents/CobaltIngestion/voice-insights-azureapi/docs/presentation';
const logo = fs.readFileSync(OUT + '/assets/aia-voice-insights-wordmark-red.png');

const RED = 'D31145', INK = '1D1D1F', GREY = '6E6E73', MIST = 'F5F5F7', LINE = 'D9D9DE', TEAL = '14825A';
const FONT = 'Arial';
const CW = 9638; // A4 with 2 cm margins, in DXA

const run = (t, o = {}) => new TextRun(Object.assign({ text: t, font: FONT, size: 20, color: INK }, o));
const para = (children, o = {}) => new Paragraph(Object.assign({ spacing: { after: 120, line: 280 }, children: Array.isArray(children) ? children : [run(children)] }, o));
const h1 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_1, spacing: { before: 300, after: 120 }, keepNext: true, children: [run(t, { size: 28, bold: true, color: RED })] });
const h2 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_2, spacing: { before: 220, after: 100 }, keepNext: true, children: [run(t, { size: 23, bold: true })] });
const bullet = (t, level = 0) => new Paragraph({ numbering: { reference: 'bullets', level }, spacing: { after: 70, line: 275 }, children: Array.isArray(t) ? t : [run(t)] });
const bold = (t) => run(t, { bold: true });

const border = { style: BorderStyle.SINGLE, size: 4, color: LINE };
const borders = { top: border, bottom: border, left: border, right: border };
function cell(content, w, o = {}) {
  const kids = (Array.isArray(content) ? content : [content]).map((c) => (typeof c === 'string'
    ? new Paragraph({ spacing: { after: 40, line: 270 }, children: [run(c, Object.assign({ size: 18 }, o.run || {}))] }) : c));
  return new TableCell({
    width: { size: w, type: WidthType.DXA }, borders,
    shading: o.fill ? { fill: o.fill, type: ShadingType.CLEAR, color: 'auto' } : undefined,
    margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: kids,
  });
}
function table(widths, header, rows) {
  const total = widths.reduce((a, b) => a + b, 0);
  return new Table({
    width: { size: total, type: WidthType.DXA }, columnWidths: widths,
    rows: [
      new TableRow({ tableHeader: true, children: header.map((h, i) => cell(h, widths[i], { fill: RED, run: { bold: true, color: 'FFFFFF' } })) }),
      ...rows.map((r) => new TableRow({ cantSplit: true, children: r.map((c, i) => cell(c, widths[i], i === 0 ? { run: { bold: true } } : {})) })),
    ],
  });
}
const spacer = () => new Paragraph({ spacing: { after: 120 }, children: [] });
const callout = (title, lines) => new Table({
  width: { size: CW, type: WidthType.DXA }, columnWidths: [CW],
  rows: [new TableRow({ children: [new TableCell({
    width: { size: CW, type: WidthType.DXA }, borders: { top: border, bottom: border, left: { style: BorderStyle.SINGLE, size: 4, color: RED }, right: border },
    shading: { fill: MIST, type: ShadingType.CLEAR, color: 'auto' }, margins: { top: 140, bottom: 100, left: 200, right: 200 },
    children: [new Paragraph({ spacing: { after: 100 }, children: [run(title, { bold: true, size: 23, color: RED })] }), ...lines],
  })] })],
});

const children = [
  // ── title block ──
  new Paragraph({ spacing: { after: 240 }, children: [new ImageRun({ type: 'png', data: logo, transformation: { width: 200, height: 16.6 }, altText: { title: 'AIA Voice Insights', description: 'AIA Voice Insights logo', name: 'logo' } })] }),
  new Paragraph({ spacing: { after: 80 }, children: [run('Executive pre-read', { size: 20, color: GREY, bold: true })] }),
  new Paragraph({ spacing: { after: 120 }, children: [run('Every conversation, remembered', { size: 48, bold: true })] }),
  para([run('AIA Voice Insights: a debrief assistant for advisors, and a request to run a controlled pilot', { size: 24, color: GREY })], { spacing: { after: 200 } }),
  table([2300, CW - 2300], ['For', 'Details'], [
    ['Readers', 'Group CEO, CTO, COO, CFO and board members'],
    ['Read before', 'The AIA Global Innovation Conference session (about 15 minutes, including a live demo)'],
    ['Scope', 'Voice Insights only: the web app (voice-insights-ui) and its API (voice-insights-azureapi)'],
    ['Status', 'Draft for review. A working prototype exists. Figures marked as estimates, assumptions or targets are planning inputs, not results.'],
  ]),
  spacer(),

  // ── decisions ──
  callout('Decisions we will ask for', [
    bullet([bold('Approve a controlled pilot'), run(' with 10 to 20 advisors, measured against a baseline taken first.')]),
    bullet([bold('Name owners'), run(' in Compliance, Legal, Information Security and Data Privacy to review the pilot before and during it.')]),
    bullet([bold('Confirm the environment'), run(': the Azure region and Azure OpenAI quota, and release the advisor group.')]),
  ]),

  // ── 1 summary ──
  h1('1. Summary'),
  para('After every customer meeting, advisors write notes, complete a fact-find, draft a record of advice, send a follow-up and update the CRM. Most of it depends on memory and spare time. Voice Insights turns the conversation itself into structured insight and draft documents.'),
  para([run('Its assistant, '), bold('Juno'), run(', works for the advisor and never speaks to the customer. After a meeting the advisor talks, in their own words. Juno says what it understood, asks at most five short questions about what is missing or unclear, and reads it back. The advisor confirms every fact on a sign-off screen. '), bold('Only a person can start the analysis or sign off the advice pack.')]),
  para([run('A new feature, '), bold('Dress Rehearsal'), run(', lets an advisor practise the next meeting against a simulation of the customer they have just debriefed, with a live coach who flags promises and pressure the moment they are said.')]),
  para('The question the pilot answers is simple: does this save advisor time and improve the quality of conversations and records, safely enough to scale?'),

  // ── 2 problem ──
  h1('2. The problem'),
  bullet([bold('Advisors: '), run('hours go to admin after each meeting, so time that could go to customers goes to typing.')]),
  bullet([bold('Customers: '), run('follow-up is slow and uneven, and what they said can be lost or summarised differently each time. Customers who prefer Mandarin, Malay or Tamil are served less consistently.')]),
  bullet([bold('Compliance: '), run('a promise of returns or an unsuitable comparison is usually found in a file review, long after the conversation. Missing facts are easy to overlook.')]),
  bullet([bold('Leadership: '), run('there is little view of how many conversations happen, how good they are, or how long it takes to move from meeting to advice.')]),
  para([run('How big each problem is remains a hypothesis until measured. Our planning assumption is about 45 minutes of admin per case. '), bold('The pilot starts by measuring the real number'), run(' with the advisors who will use the tool.')], { spacing: { before: 100, after: 140, line: 300 } }),

  // ── 3 what it does ──
  h1('3. What Voice Insights does'),
  h2('Three ways in'),
  table([2300, 2200, CW - 4500], ['Mode', 'Who speaks', 'What the app does'], [
    ['After the meeting', 'The advisor, earlier', 'The advisor dictates a summary; the app structures it and shows what a good debrief covers.'],
    ['With the customer', 'The advisor, live', 'Listens to the real conversation, suggests the next question and flags risky statements as they happen.'],
    ['Debrief with Juno', 'The advisor, earlier', 'The advisor dictates freely; Juno checks for gaps, asks at most five questions and reads back.'],
  ]),
  h2('Debrief with Juno, step by step'),
  bullet([bold('Dictate. '), run('The advisor briefs Juno the way they would brief a colleague. Juno stays quiet and listens, in English, Mandarin, Malay or Tamil.')]),
  bullet([bold('A live fact-find ledger. '), run('Every fact Juno has appears with the words the advisor used. The ledger shows how much of the 26-field fact-find is captured and what is still open. It is an estimate; the advice pack makes the final count.')]),
  bullet([bold('Juno checks for gaps. '), run('At most five short questions, one at a time, only about what is missing. The advisor can skip at any point.')]),
  bullet([bold('Compliance catch. '), run('If the advisor says a customer will "definitely be approved", Juno raises it, quotes it and offers a compliant way to record it. The flag carries into a conduct note and a clarifying sentence in the follow-up draft.')]),
  h2('Sign-off: the advisor takes control'),
  para('The sign-off screen shows what the advisor said, with names, numbers and risky promises marked where they were spoken. The advisor confirms or fixes each fact in one tap, sees what is still open, and reviews a follow-up message for the customer in four languages. Nothing is sent from the app. One button starts the analysis, and it responds only to a real person.'),
  h2('Dress Rehearsal: practise before it counts'),
  bullet([bold('Brief. '), run('In under a minute, Juno tells the advisor who they are seeing, what matters to the customer, what the advisor said that needs correcting, and how to open.')]),
  bullet([bold('Rehearse. '), run('Juno plays the customer, using only what the advisor said in the debrief. The advisor answers by voice or by typing. A coach flags promises and pressure the moment they are said.')]),
  bullet([bold('Coach’s notes. '), run('What landed, what was missed, risky wording with a better version, and a sentence to open the real meeting with.')]),
  para('It is training only. It is labelled a simulation, nothing from it is saved as a customer record, and nothing is sent to anyone.', { spacing: { before: 60, after: 140, line: 300 } }),
  h2('What the advisor receives after sign-off'),
  para('A suggestion analysis from 11 specialist agents (three running in parallel), with evidence from the product documents; an advisory report and customer proposal; and an advice pack of fact-find, record of advice, follow-up messages, CRM note with tasks, and a brief for the next meeting. Leadership gets an admin dashboard of volume, needs, sentiment and compliance flags.'),

  // ── 4 controls ──
  h1('4. Why it is safe to try'),
  para('Juno used to host the first conversation with the customer. Feedback from the first demo was that when an advisor and an AI host are in the same place, customers may prefer the advisor. We agreed and changed what Juno is for. That one decision removed the highest-risk part of the earlier design: nothing the AI says now reaches a customer.'),
  table([2900, 4500, CW - 7400], ['Control', 'How it works', 'Status'], [
    ['Juno speaks only to the advisor', 'It asks and reads back. It has no channel to the customer.', 'Built'],
    ['No prices, returns or products', 'Prompt rules, and a wording safety net applied to everything shown.', 'Built; no automatic output filter yet'],
    ['"Suggest", never "recommend"', 'Relevance is shown in words, never a score or percentage, following MAS wording guidance.', 'Built'],
    ['Only a person starts the analysis', 'The button ignores scripted clicks.', 'Built'],
    ['Every fact confirmed', 'The advisor confirms or edits names and numbers on the sign-off screen.', 'Built'],
    ['Nothing sent automatically', 'Follow-ups are drafts; the advisor sends them.', 'Built'],
    ['Personal identifiers', 'ID and card numbers are masked before anything is stored.', 'Built'],
    ['Audit trail', 'Every agent step’s input and output is stored.', 'Built'],
    ['Formal review', 'Compliance, Legal, Security and PDPA review of prompts, data flows and retention.', 'Not yet done. Part of the pilot'],
  ]),
  para('These controls work in the prototype and are pilot-grade, not finished. We are not asking the Board to rely on them without review. We are asking for the people who own those risks to review them as part of the pilot.', { spacing: { before: 120, after: 140, line: 300 } }),

  // ── 5 architecture ──
  h1('5. How it is built'),
  para('Standard parts, chosen so that it scales by adding capacity rather than by redesign.'),
  table([2500, CW - 2500], ['Layer', 'Technology'], [
    ['Advisor’s browser', 'React 18 and TypeScript, served from a container'],
    ['API', 'Java 21, Spring Boot and Spring AI'],
    ['Agents', 'LangGraph4j: 11 specialist agents, three in parallel, each step stored'],
    ['AI services', 'Azure OpenAI for speech to text, reasoning, meaning search and Juno’s voice'],
    ['Data', 'PostgreSQL with pgvector for profiles, analysis runs, advice packs and product-document search; Redis for sessions'],
    ['Delivery', 'Docker containers; Azure Container Apps or AKS for the pilot'],
  ]),

  // ── 6 evidence ──
  h1('6. What we know so far'),
  h2('Built and demonstrated'),
  para('Three capture modes, the Juno debrief with the fact-find ledger, the sign-off screen, Dress Rehearsal, the 11-agent analysis and advice pack, drafts in four languages, and an admin dashboard.'),
  h2('Tested'),
  para('Scripted spoken debriefs and rehearsals, in English, with a synthetic voice. In those tests Juno caught a deliberately planted promise, the fact-find ledger rose after Juno’s questions, and a short debrief reached the sign-off screen in under two minutes. These were short scenarios, so they are a check that the product works, not evidence of time saved in real meetings. Juno and the rehearsal customer answer in roughly 4 to 8 seconds, and a noisy room makes speech recognition less reliable.'),
  h2('Not yet done'),
  bullet('Testing with real advisors and real meetings.'),
  bullet('Native-speaker review of Mandarin, Malay and Tamil wording.'),
  bullet('Formal Compliance, Security and privacy review.'),
  bullet('CRM write-back, and load testing at pilot scale.'),
  bullet('Measured time savings. This needs the baseline first.'),

  // ── 7 value ──
  h1('7. Value, and how we will measure it'),
  h2('Proposed pilot targets (to be agreed)'),
  table([5000, CW - 5000], ['Measure', 'Proposed target'], [
    ['Time from meeting to documented advice', 'At least 50% lower than the baseline'],
    ['Follow-up sent within 24 hours', '90% of pilot cases'],
    ['Fact-find fields captured or flagged as missing', 'At least 80% completeness'],
    ['Advice packs reviewed and signed off by the advisor', '100%. No pack used unsigned'],
    ['Fact-find completeness with Juno, against dictation alone', 'Higher, for the same advisors'],
    ['Advisor satisfaction', '4 out of 5 or better'],
    ['Time to a full suggestion analysis', 'Under 60 seconds'],
    ['Running cost per conversation', 'Under SGD 1'],
  ]),
  para('The two debrief modes are recorded separately, so the pilot can compare them directly. If Juno’s mode is both faster and more complete, the dictation-only mode can be retired later. If advisors prefer to dictate alone, we keep both.', { spacing: { before: 120, after: 140, line: 300 } }),
  h2('An illustration of scale (not a forecast)'),
  table([5000, CW - 5000], ['Input', 'Example value'], [
    ['Advisors using the tool', '200'],
    ['Cases per advisor per month', '8'],
    ['Admin minutes saved per case', '45 (an assumption, to be replaced by the baseline)'],
    ['Hours returned to customer work each month', '1,200, about 7.5 full-time equivalents'],
  ]),
  h2('What it costs (planning estimates for Finance to confirm)'),
  table([5000, CW - 5000], ['Item', 'Estimate'], [
    ['Running one conversation', 'About SGD 0.35 to 0.60 (public list prices; check against AIA’s Azure agreement)'],
    ['Four-month proof-of-concept planning range', 'About SGD 241,000 to 350,000, mostly team time'],
    ['Extra cash if the team is existing AIA staff', 'About SGD 34,000 to 74,000, including contingency'],
  ]),

  // ── 8 pilot ──
  h1('8. Proposed pilot'),
  para('Proposed shape, for the pilot owners to confirm.'),
  table([2000, 1500, CW - 3500], ['Phase', 'Duration', 'What happens'], [
    ['Baseline and review', '2 weeks', 'Time study with the pilot advisors. Compliance and Privacy review the prompts, wording, consent practice and retention. Targets agreed.'],
    ['Pilot', '8 weeks', '10 to 20 advisors use both debrief modes on real cases. Weekly check-ins. Every pack signed off by the advisor.'],
    ['Evaluate', '2 weeks', 'Results against the targets. A recommendation to scale, adjust or stop.'],
  ]),

  // ── 9 risks ──
  h1('9. Risks'),
  table([2900, 1200, CW - 4100], ['Risk', 'Level', 'How it is handled'], [
    ['AI says something non-compliant (a price, a guarantee)', 'Lower now', 'Juno speaks only to the advisor; scripted test pack; Compliance review before the pilot; every output reviewable.'],
    ['Personal data and consent (PDPA)', 'High', 'Privacy assessment, retention rules and advisor-side consent practice agreed with Data Privacy first.'],
    ['Names and accents misheard', 'Medium', 'Every fact confirmed on screen; native-speaker testing for Mandarin, Malay and Tamil.'],
    ['Slower than dictating', 'Medium', 'At most five questions, always skippable; the pilot compares both modes on time and completeness.'],
    ['Azure capacity or stalls at pilot load', 'Medium', 'Request quota early; automatic retries and a second attempt on slow calls; fallback voice.'],
    ['Advisors do not adopt it', 'Medium', 'Involve advisors from the start; measure time saved with them; the advisor stays the decision-maker.'],
  ]),

  // ── 10 needs ──
  h1('10. What we need from AIA'),
  bullet('An Azure subscription and region approved for the data in scope, with Azure OpenAI quota.'),
  bullet('Product documents and approved wording for the products in the pilot.'),
  bullet('Named contacts in Compliance, Legal, Information Security and Data Privacy.'),
  bullet('A CRM sandbox, if CRM write-back is in scope; and single sign-on details if required.'),
  bullet('Advisor volunteers, and their managers’ agreement to take part in the time study and pilot.'),
  para([run('Figures marked as estimates, assumptions or targets are planning inputs and should be confirmed with Finance, Compliance and the pilot baseline. Behaviour described as working was observed in the current prototype. Source material: the Voice Insights POC overview, the Debrief with Juno plan and the technical guide in this repository.', { size: 17, color: GREY, italics: true })], { spacing: { before: 240, after: 0, line: 260 } }),
];

const doc = new Document({
  creator: 'AIA Voice Insights', title: 'AIA Voice Insights: Executive pre-read',
  styles: { default: { document: { run: { font: FONT, size: 21 } } } },
  numbering: { config: [{ reference: 'bullets', levels: [{ level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 270 } } } }] }] },
  sections: [{
    properties: { page: { size: { width: 11906, height: 16838 }, margin: { top: 1134, right: 1134, bottom: 1134, left: 1134 } } },
    footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.RIGHT, children: [run('AIA Voice Insights · Executive pre-read · Draft · Page ', { size: 16, color: GREY }), new TextRun({ children: [PageNumber.CURRENT], font: FONT, size: 16, color: GREY })] })] }) },
    children,
  }],
});

Packer.toBuffer(doc).then((buf) => {
  fs.writeFileSync(OUT + '/Executive-Pre-Read.docx', buf);
  console.log('written', buf.length);
});
