package com.learnthink.core.directanswer.domain.response;

/**
 * 直接解答段落信息（历史回放用，轻量 DTO）。
 */
public record DirectAnswerSectionInfo(
    String sectionId,
    String title,
    String content,
    int sectionOrder
) {}
