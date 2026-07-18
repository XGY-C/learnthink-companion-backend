package com.learnthink.core.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import com.learnthink.core.domain.entity.BookInfo;
import com.learnthink.core.domain.entity.KnowledgeDocument;
import com.learnthink.core.repository.BookInfoMapper;
import com.learnthink.core.repository.KnowledgeDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教材结构检索工具——返回课程教材的目录和简介
 * <p>单一工具、单一动作：{@code get_book_info} 同时返回完整目录树和简介文本。</p>
 */
public class BookInfoTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(BookInfoTool.class);

    private final KnowledgeDocumentMapper documentMapper;
    private final BookInfoMapper bookInfoMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    public BookInfoTool(KnowledgeDocumentMapper documentMapper, BookInfoMapper bookInfoMapper) {
        this.documentMapper = documentMapper;
        this.bookInfoMapper = bookInfoMapper;
    }

    @Override
    public String name() {
        return "get_book_info";
    }

    @Override
    public String description() {
        return "获取课程教材的结构信息，包括完整章节目录树和内容简介全文。" +
               "当学生询问课程结构、特定章节内容、或问'这门课讲什么'时调用此工具。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {},
              "required": []
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = mapper.readValue(jsonArgs, Map.class);
            String courseId = (String) args.get("course_id");

            BookInfo bookInfo = resolveBookInfo(courseId);
            if (bookInfo == null) {
                return mapper.writeValueAsString(Map.of(
                    "toc", "[]",
                    "introduction", "",
                    "error", "该课程暂无教材信息"
                ));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("title", bookInfo.getTitle() != null ? bookInfo.getTitle() : "");
            result.put("toc", bookInfo.getToc() != null ? bookInfo.getToc() : "[]");
            result.put("introduction", bookInfo.getIntroduction() != null ? bookInfo.getIntroduction() : "");
            result.put("author", bookInfo.getAuthor() != null ? bookInfo.getAuthor() : "");
            return mapper.writeValueAsString(result);

        } catch (JsonProcessingException e) {
            log.error("BookInfoTool: failed to parse args", e);
            return "{\"toc\":\"[]\",\"introduction\":\"\",\"error\":\"INVALID_ARGS\"}";
        }
    }

    /**
     * 通过 courseId → KnowledgeDocument → BookInfo 链解析课程教材信息
     * <p>公开方法，以便调用方（如系统提示词构建器）复用同一查找逻辑。</p>
     *
     * @return 课程教材信息，不存在时返回 null
     */
    public BookInfo resolveBookInfo(String courseId) {
        if (courseId == null || courseId.isBlank()) return null;
        try {
            List<KnowledgeDocument> docs = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getCourseId, courseId)
                    .eq(KnowledgeDocument::getSourceType, "主教材"));
            if (docs.isEmpty()) {
                log.debug("No KnowledgeDocument with source_type=主教材 for courseId={}", courseId);
                return null;
            }
            for (KnowledgeDocument doc : docs) {
                BookInfo bookInfo = bookInfoMapper.selectOne(
                    new LambdaQueryWrapper<BookInfo>()
                        .eq(BookInfo::getDocumentId, doc.getId()));
                if (bookInfo != null) return bookInfo;
                log.debug("No BookInfo for 主教材 documentId={}, try next", doc.getId());
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to resolve BookInfo for courseId={}: {}", courseId, e.getMessage());
            return null;
        }
    }
}
