package com.learnthink.core.service.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文档切分工具.
 *
 * <p>提供两种切分策略:</p>
 * <ul>
 *   <li>{@link #split(String)} — 按一级标题(#)智能切分，适用于直接上传的MD教材.
 *       区分目录中的章标题(含……或尾随页码)与正文中的章标题.</li>
 *   <li>{@link #splitByH2(String)} — 按二级标题(##)切分，适用于Mineru解析PDF输出的full.md.</li>
 * </ul>
 */
public class MarkdownSplitter {

    private static final Pattern H1 = Pattern.compile("^#\\s+(.+)$");

    private static final Pattern H2 = Pattern.compile("^## (?!#)(.+)$", Pattern.MULTILINE);

    private static final Pattern CHAPTER = Pattern.compile("^# 第\\s*\\d+\\s*章\\s+(.+)$");

    private static final Pattern SPECIAL = Pattern.compile("^(目录|前言|参考文献|附录\\s*[AB])");

    // ======================== H1 智能切分（直接上传的MD教材） ========================

    /**
     * 按一级标题(#)智能切分，适用于直接上传的MD教材.
     *
     * <p>切分触发条件:</p>
     * <ul>
     *   <li>特殊标题(精确匹配): 目录 / 前言 / 附录 A / 附录 B / 参考文献</li>
     *   <li>正文章节标题: # 第 X 章 ... (不含……/.., 且行尾无页码数字)</li>
     * </ul>
     */
    public static List<ChapterInfo> split(String markdown) {
        List<ChapterInfo> chapters = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return chapters;

        String[] lines = markdown.split("\n", -1);
        List<Integer> splitLines = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            if (isH1Trigger(lines[i].strip())) {
                splitLines.add(i);
            }
        }

        if (splitLines.isEmpty()) {
            chapters.add(new ChapterInfo(extractFirstH1(markdown), markdown.strip()));
            return chapters;
        }

        for (int s = 0; s < splitLines.size(); s++) {
            int start = splitLines.get(s);
            int end = (s + 1 < splitLines.size()) ? splitLines.get(s + 1) : lines.length;

            StringBuilder content = new StringBuilder();
            for (int i = start; i < end; i++) {
                content.append(lines[i]).append("\n");
            }

            String headingText = h1Text(lines[start].strip());
            chapters.add(new ChapterInfo(headingText, content.toString().strip()));
        }

        return chapters;
    }

    static boolean isH1Trigger(String line) {
        Matcher m = H1.matcher(line);
        if (!m.matches()) return false;
        String text = m.group(1).strip();

        if (SPECIAL.matcher(text).find()) return true;

        Matcher cm = CHAPTER.matcher(line);
        if (!cm.matches()) return false;

        // 目录条目含 … 或 .. 或行尾页码数字 → 不触发切分
        if (text.contains("…") || text.contains("..")) return false;
        // 行尾有空白+数字（页码），如 "第 1 章 绪论 23"
        if (text.matches(".*\\s+\\d+$")) return false;

        return true;
    }

    static String h1Text(String line) {
        Matcher m = H1.matcher(line);
        if (m.matches()) return m.group(1).strip();
        return line.strip();
    }

    private static String extractFirstH1(String markdown) {
        Matcher m = H1.matcher(markdown);
        if (m.find()) return m.group(1).strip();
        for (String line : markdown.split("\n")) {
            String s = line.strip();
            if (!s.isEmpty()) return s;
        }
        return "全文";
    }

    // ======================== H2 切分（Mineru解析PDF输出） ========================

    /**
     * 按二级标题(##)切分，适用于Mineru解析PDF生成的full.md.
     */
    public static List<ChapterInfo> splitByH2(String markdown) {
        List<ChapterInfo> chapters = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return chapters;

        Matcher matcher = H2.matcher(markdown);

        if (!matcher.find()) {
            chapters.add(new ChapterInfo(extractFirstH1(markdown), markdown.strip()));
            return chapters;
        }

        matcher.reset();

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

        String tail = markdown.substring(lastEnd).strip();
        if (!tail.isEmpty()) {
            chapters.add(new ChapterInfo(lastTitle, tail));
        }

        return chapters;
    }

    // ======================== 通用工具 ========================

    /**
     * 净化文件名.
     */
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
