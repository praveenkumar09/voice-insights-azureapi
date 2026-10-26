// Wraps the rendered one-slide PNG in a 16:9 PowerPoint file so it drops straight into a deck.
const pptxgen = require('pptxgenjs'); const path = require('path');
const D = path.resolve(__dirname, '../..');
const p = new pptxgen(); p.layout = 'LAYOUT_WIDE'; p.title = 'AIA Voice Insights: one-slide EXCO view';
const s = p.addSlide(); s.addImage({ path: D + '/EXCO-One-Slide-Voice-Insights.png', x: 0, y: 0, w: 13.333, h: 7.5, altText: 'AIA Voice Insights one-slide EXCO view' });
p.writeFile({ fileName: D + '/EXCO-One-Slide-Voice-Insights.pptx' }).then(() => console.log('pptx ok'));
