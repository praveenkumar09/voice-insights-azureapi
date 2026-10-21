package com.aia.voiceinsights.api.knowledge;


import java.util.ArrayList;
import java.util.List;

/**
 * Category-aware chunker for the extracted plain text of a single PDF.
 *
 * Works line-by-line rather than on blank-line-separated paragraphs: PDFBox's
 * {@code PDFTextStripper} groups output by glyph position, so lines with no
 * glyphs (blank lines in the source) are dropped entirely — there is no
 * blank-line signal left to split on once text has been extracted.
 *
 * FAQ documents are split one chunk per Q&amp;A pair, detected by lines
 * starting with "Q:", since merging the whole FAQ into one chunk would blur
 * unrelated answers together in a single embedding. Every other category is
 * split by ALL-CAPS heading lines (e.g. "KEY BENEFITS") into per-section
 * chunks, further split on line boundaries — carrying the last line forward
 * as overlap — if a section exceeds {@code maxChars}.
 */
public class DocumentChunker {

    public record Section(String title, String content) {}

    private final int maxChars;

    public DocumentChunker(int maxChars) {
        this.maxChars = maxChars;
    }

    public List<Section> chunk(String fullText, DocCategory category) {
        // Clean first (NUL bytes make Postgres reject the row), then make sure no single line is longer than a chunk: a PDF whose
        // text has no line breaks used to become one huge chunk that the embedding model refused (8192-token limit).
        List<String> lines = clean(fullText).lines().map(String::trim).filter(l -> !l.isEmpty())
                .flatMap(l -> splitLong(l).stream()).toList();
        return category == DocCategory.FAQ ? chunkFaq(lines) : chunkByHeading(lines);
    }

    /**
     * Strips what Postgres text/jsonb columns cannot hold or that is only extraction noise: NUL and other control characters
     * (tabs and line breaks are kept) and unpaired surrogates. Public so the store can apply the same rule as a last line of defence.
     */
    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                out.append(c).append(s.charAt(++i));
            } else if (Character.isSurrogate(c)) {
                // unpaired: drop
            } else if (c == '\n' || c == '\t' || c == '\r' || (c >= 0x20 && c != 0x7f && !(c >= 0x80 && c < 0xa0) && c != 0xfffe && c != 0xffff)) {
                out.append(c);
            } else if (c != 0) {
                out.append(' ');
            }
        }
        return out.toString();
    }

    /** Cuts text longer than maxChars into pieces of at most maxChars, preferably at a space. */
    private List<String> splitLong(String text) {
        if (text.length() <= maxChars) return List.of(text);
        List<String> out = new ArrayList<>();
        String rest = text;
        while (rest.length() > maxChars) {
            int cut = rest.lastIndexOf(' ', maxChars);
            if (cut < maxChars / 2) cut = maxChars; // no usable space (e.g. unbroken text): hard cut
            if (cut > 0 && Character.isHighSurrogate(rest.charAt(cut - 1))) cut--;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    private boolean isHeading(String line) {
        if (line.isBlank() || line.length() > 70) return false;
        return line.equals(line.toUpperCase()) && line.chars().anyMatch(Character::isLetter);
    }

    private List<Section> chunkFaq(List<String> lines) {
        List<Section> sections = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String line : lines) {
            if (isHeading(line)) continue; // e.g. the bare "FREQUENTLY ASKED QUESTIONS" banner
            if (line.startsWith("Q:") && buffer.length() > 0) {
                flush(sections, buffer);
                buffer.setLength(0);
            }
            if (buffer.length() > 0) buffer.append(' ');
            buffer.append(line);
        }
        flush(sections, buffer);
        return sections;
    }

    /** FAQ sections don't have a separate title carried in — derive it from the question text itself. */
    private void flush(List<Section> sections, StringBuilder buffer) {
        String content = buffer.toString().trim();
        if (content.isBlank()) return;
        String title = extractQuestion(content);
        for (String piece : splitLong(content)) sections.add(new Section(title, piece));
    }

    private String extractQuestion(String qaText) {
        int aIdx = qaText.indexOf(" A:");
        String q = aIdx > 0 ? qaText.substring(0, aIdx) : qaText;
        return q.length() > 90 ? q.substring(0, 90) + "…" : q;
    }

    private List<Section> chunkByHeading(List<String> lines) {
        List<Section> sections = new ArrayList<>();
        String currentTitle = "Overview";
        StringBuilder buffer = new StringBuilder();
        String lastLine = null;

        for (String line : lines) {
            if (isHeading(line)) {
                flushSection(sections, currentTitle, buffer);
                currentTitle = titleCase(line);
                buffer.setLength(0);
                lastLine = null;
                continue;
            }
            if (buffer.length() > 0 && buffer.length() + line.length() + 1 > maxChars) {
                flushSection(sections, currentTitle, buffer);
                buffer.setLength(0);
                if (lastLine != null) buffer.append(lastLine).append(' ');
            }
            if (buffer.length() > 0) buffer.append(' ');
            buffer.append(line);
            lastLine = line;
        }
        flushSection(sections, currentTitle, buffer);
        return sections;
    }

    private void flushSection(List<Section> sections, String title, StringBuilder buffer) {
        String content = buffer.toString().trim();
        if (!content.isBlank()) sections.add(new Section(title, content));
    }

    private String titleCase(String upper) {
        String[] words = upper.toLowerCase().split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
