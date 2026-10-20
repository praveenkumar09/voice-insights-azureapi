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
        List<String> lines = fullText.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
        return category == DocCategory.FAQ ? chunkFaq(lines) : chunkByHeading(lines);
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
        sections.add(new Section(extractQuestion(content), content));
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
