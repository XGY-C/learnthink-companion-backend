package com.learnthink.core.service.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits Mineru-generated full.md into chapters by ## (H2) headings.
 */
public class MarkdownSplitter {

    private static final Pattern H2_PATTERN = Pattern.compile("^## (?!#)(.+)$", Pattern.MULTILINE);
    private static final Pattern H1_PATTERN = Pattern.compile("^# (.+)$", Pattern.MULTILINE);

    public static List<ChapterInfo> split(String markdown) {
        List<ChapterInfo> chapters = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return chapters;

        Matcher matcher = H2_PATTERN.matcher(markdown);

        if (!matcher.find()) {
            // No H2 headings → single chapter
            chapters.add(new ChapterInfo(extractTitle(markdown), markdown.strip()));
            return chapters;
        }

        matcher.reset();

        // Content before the first H2 → "概述"
        if (matcher.find() && matcher.start() > 0) {
            String preamble = markdown.substring(0, matcher.start()).strip();
            if (!preamble.isEmpty()) {
                chapters.add(new ChapterInfo("概述", preamble));
            }
        } else {
            matcher.reset();
            matcher.find();
        }

        String lastTitle = matcher.group(1).strip();
        int lastEnd = matcher.end();

        while (matcher.find()) {
            String content = markdown.substring(lastEnd, matcher.start()).strip();
            if (!content.isEmpty()) {
                chapters.add(new ChapterInfo(lastTitle, content));
            }
            lastTitle = matcher.group(1).strip();
            lastEnd = matcher.end();
        }

        // Content after the last H2
        String tail = markdown.substring(lastEnd).strip();
        if (!tail.isEmpty()) {
            chapters.add(new ChapterInfo(lastTitle, tail));
        }

        return chapters;
    }

    private static String extractTitle(String markdown) {
        Matcher m = H1_PATTERN.matcher(markdown);
        if (m.find()) {
            return m.group(1).strip();
        }
        return "全文";
    }

    public static String sanitizeFileName(String title) {
        return title
                .replaceAll("[\\\\/:*?\"<>|]", "")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .replaceAll("[^\\w\\u4e00-\\u9fff\\-.()]", "")
                .substring(0, Math.min(80, title.length()))
                + ".md";
    }
}
