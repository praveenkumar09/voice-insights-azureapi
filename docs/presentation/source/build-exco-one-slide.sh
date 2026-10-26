#!/bin/zsh
# Builds the one-slide EXCO view (PDF + PNG) from exco-one-slide.html with headless Chrome.
set -e
HERE=${0:A:h}; DOCS=$HERE/../..; T=${TMPDIR:-/tmp}/exco-one-slide.html
MASCOT=/Users/praveenkumarthangaraj/Documents/CobaltIngestion/voice-insights-ui/public/aia-voice-insights-logo.png
B64="data:image/png;base64,$(base64 -i $MASCOT | tr -d '\n')"
python3 - "$HERE/exco-one-slide.html" "$T" "$B64" <<'P'
import sys;s=open(sys.argv[1]).read().replace('__MASCOT__',sys.argv[3]);open(sys.argv[2],'w').write(s)
P
C="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$C" --headless=new --disable-gpu --no-pdf-header-footer --print-to-pdf=$DOCS/EXCO-One-Slide-Voice-Insights.pdf file://$T 2>/dev/null
"$C" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --window-size=1920,1080 --screenshot=$DOCS/EXCO-One-Slide-Voice-Insights.png file://$T 2>/dev/null
