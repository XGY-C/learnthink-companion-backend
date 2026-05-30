package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * Spring AI {@link ToolCallback} 包装器——用于教材信息检索
 * <p>与 {@link RagToolCallback} 一样按次注册（不作为 Spring Bean），
 * 因此仅出现在对话 LLM 的工具集中。</p>
 */
public class BookInfoToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(BookInfoToolCallback.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BookInfoTool bookInfoTool;
    private final String courseId;

    public BookInfoToolCallback(BookInfoTool bookInfoTool, String courseId) {
        this.bookInfoTool = bookInfoTool;
        this.courseId = courseId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("get_book_info")
            .description("获取课程教材的结构信息，包括完整章节目录树（含所有小节标题）和内容简介全文。" +
                         "当学生询问课程结构、特定章节内容、或问'这门课讲什么'时调用此工具。" +
                         "返回包含 toc（目录树JSON）、introduction（简介全文）、title（书名）、author（作者）的JSON对象。")
            .inputSchema("""
                {
                  "type": "object",
                  "properties": {},
                  "required": [],
                  "additionalProperties": false
                }""")
            .build();
    }

    @Override
    public String call(String functionInput) {
        log.info("BookInfoToolCallback invoked: courseId={}", courseId);
        try {
            String args = MAPPER.writeValueAsString(Map.of("course_id", courseId));
            return bookInfoTool.execute(args);
        } catch (Exception e) {
            log.error("BookInfoToolCallback failed: {}", e.getMessage());
            return "{\"toc\":\"[]\",\"introduction\":\"\",\"error\":\"" +
                e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
