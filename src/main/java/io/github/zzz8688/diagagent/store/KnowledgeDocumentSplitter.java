package io.github.zzz8688.diagagent.store;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class KnowledgeDocumentSplitter {

    @Value("${knowledge.rag.chunk.max-segment-size:300}")
    private int maxSegmentSize;

    @Value("${knowledge.rag.chunk.overlap-size:50}")
    private int overlapSize;

    public List<TextSegment> splitForKnowledgeFile(String text, String source, String type) {
        String id = source;
        String title = source;
        if (source.endsWith(".md") || source.endsWith(".pdf")) {
            int dotIndex = source.lastIndexOf('.');
            if (dotIndex > 0) {
                id = source.substring(0, dotIndex);
                title = id;
            }
        }
        title = resolveDocumentTitle(text, source, title);
        return split(text, id, title, source, type);
    }

    public List<TextSegment> splitForDocument(String text, String documentId, String documentTitle, String sourceType) {
        return split(text, documentId, documentTitle, sourceType, sourceType);
    }

    public List<TextSegment> split(String text, String id, String title, String source, String type) {
        List<TextSegment> segments = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return segments;
        }

        String[] paragraphs = text.split("\n\n");
        StringBuilder currentSegment = new StringBuilder();
        int currentSize = 0;

        for (String paragraph : paragraphs) {
            String cleanedParagraph = paragraph.trim();
            if (cleanedParagraph.isEmpty()) {
                continue;
            }

            if (cleanedParagraph.length() > maxSegmentSize) {
                if (currentSize > 0) {
                    addSegment(segments, currentSegment.toString(), id, title, source, type);

                    String overlap = currentSegment.toString();
                    currentSize = Math.min(overlap.length(), overlapSize);
                    currentSegment = new StringBuilder(overlap.substring(overlap.length() - currentSize));
                    currentSize = currentSegment.length();
                }

                for (String part : splitLongParagraph(cleanedParagraph)) {
                    addSegment(segments, part, id, title, source, type);
                }

                currentSegment = new StringBuilder();
                currentSize = 0;
                continue;
            }

            int paragraphSize = cleanedParagraph.length();
            if (currentSize + paragraphSize > maxSegmentSize && currentSize > 0) {
                addSegment(segments, currentSegment.toString(), id, title, source, type);

                String overlap = currentSegment.toString();
                currentSize = Math.min(overlap.length(), overlapSize);
                currentSegment = new StringBuilder(overlap.substring(overlap.length() - currentSize));
                currentSize = currentSegment.length();
            }

            currentSegment.append(cleanedParagraph).append("\n\n");
            currentSize += paragraphSize + 2;
        }

        if (currentSegment.length() > 0) {
            addSegment(segments, currentSegment.toString(), id, title, source, type);
        }

        return segments;
    }

    private void addSegment(List<TextSegment> segments, String content, String id, String title, String source, String type) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        Metadata metadata = new Metadata();
        metadata.put("id", id);
        metadata.put("title", title);
        metadata.put("source", source);
        metadata.put("type", type);
        segments.add(TextSegment.from(trimmed, metadata));
    }

    private String resolveDocumentTitle(String text, String source, String fallbackTitle) {
        if (text == null || text.isBlank()) {
            return fallbackTitle;
        }
        if (source != null && source.toLowerCase().endsWith(".md")) {
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#")) {
                    String heading = trimmed.replaceFirst("^#+\\s*", "").trim();
                    if (!heading.isEmpty()) {
                        return heading;
                    }
                }
            }
        }
        return fallbackTitle;
    }

    private List<String> splitLongParagraph(String paragraph) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int length = paragraph.length();

        while (start < length) {
            int end = Math.min(start + maxSegmentSize, length);

            if (end < length) {
                int lastSpace = paragraph.lastIndexOf(' ', end);
                int lastComma = paragraph.lastIndexOf(',', end);
                int lastChineseComma = paragraph.lastIndexOf('，', end);
                int lastPeriod = paragraph.lastIndexOf('.', end);
                int lastChinesePeriod = paragraph.lastIndexOf('。', end);

                int bestSplit = Math.max(
                        Math.max(lastSpace, lastComma),
                        Math.max(lastChineseComma, Math.max(lastPeriod, lastChinesePeriod))
                );
                if (bestSplit > start + maxSegmentSize / 2) {
                    end = bestSplit + 1;
                }
            }

            parts.add(paragraph.substring(start, end).trim());
            start = end;

            if (start < length) {
                start = Math.max(0, start - overlapSize);
            }
        }

        return parts;
    }
}
