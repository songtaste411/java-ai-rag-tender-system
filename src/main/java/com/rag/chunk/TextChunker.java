package com.rag.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合分块：先按章节启发式切分，过长再滑窗。
 */
public final class TextChunker {

    private TextChunker() {}

    public static List<String> hybridChunk(String text, int windowSize, int overlap, int chapterMinLen) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> chapters = splitByChapter(text);
        List<String> result = new ArrayList<>();
        for (String chapter : chapters) {
            if (chapter.length() <= chapterMinLen) {
                result.add(chapter);
            } else {
                result.addAll(slidingWindow(chapter, windowSize, overlap));
            }
        }
        return result;
    }

    static List<String> splitByChapter(String text) {
        List<String> chapters = new ArrayList<>();
        String[] raw = text.split("\\n\\n+");
        StringBuilder current = new StringBuilder();
        for (String block : raw) {
            String b = block.strip();
            if (b.isEmpty()) {
                continue;
            }
            boolean title = b.startsWith("第") || b.startsWith("一、") || b.startsWith("二、")
                    || b.startsWith("1.") || b.startsWith("2.") || b.startsWith("(1)");
            if (title && !current.isEmpty()) {
                chapters.add(current.toString().strip());
                current = new StringBuilder(b);
            } else {
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(b);
            }
        }
        if (!current.isEmpty()) {
            chapters.add(current.toString().strip());
        }
        return chapters;
    }

    static List<String> slidingWindow(String text, int windowSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int len = text.length();
        int step = Math.max(1, windowSize - overlap);
        while (start < len) {
            int end = Math.min(len, start + windowSize);
            chunks.add(text.substring(start, end));
            if (end >= len) {
                break;
            }
            start += step;
        }
        return chunks;
    }
}
