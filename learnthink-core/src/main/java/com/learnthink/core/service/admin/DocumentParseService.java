package com.learnthink.core.service.admin;

import com.learnthink.common.util.AliOSSUtil;
import com.learnthink.core.domain.entity.KnowledgeDocument;
import com.learnthink.core.repository.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档解析编排服务: PDF → Mineru → 按章拆分 → 章节MD上传OSS → 入库.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParseService {

    private final MineruService mineruService;
    private final AliOSSUtil ossUtil;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    private static final long SIGNED_URL_EXPIRY_MS = 60 * 60 * 1000L; // 60min

    /**
     * 解析PDF并上传章节MD到OSS.
     *
     * @param ossPdfUrl OSS上PDF的访问URL
     * @param courseId  所属课程ID
     * @param title     文档标题
     * @return 章节列表（含OSS URL）
     */
    @Transactional
    public List<ChapterInfo> parsePdf(String ossPdfUrl, String courseId, String title) {
        // 1. 生成OSS签名URL供Mineru下载
        String signedUrl = ossUtil.getSignedUrlWithExpiry(ossPdfUrl, SIGNED_URL_EXPIRY_MS);
        log.info("Generated signed URL for PDF: {}", ossPdfUrl);

        // 2. Mineru解析PDF → full.md
        String fullMarkdown = mineruService.parseDocument(signedUrl);
        log.info("Mineru parsed PDF, got {} chars of markdown", fullMarkdown.length());

        // 3. 按##拆分章节
        List<ChapterInfo> chapters = MarkdownSplitter.split(fullMarkdown);
        log.info("Split markdown into {} chapters", chapters.size());

        // 4. 保存原始PDF文档记录
        KnowledgeDocument pdfDoc = new KnowledgeDocument();
        pdfDoc.setCourseId(courseId);
        pdfDoc.setTitle(title);
        pdfDoc.setSourceType("教材");
        pdfDoc.setFilePath(ossPdfUrl);
        pdfDoc.setCreatedAt(LocalDateTime.now());
        knowledgeDocumentMapper.insert(pdfDoc);

        // 5. 逐个章节: 上传MD到OSS → 入库
        for (ChapterInfo chapter : chapters) {
            String mdFileName = MarkdownSplitter.sanitizeFileName(chapter.getTitle());
            String ossDir = "kb/" + courseId + "/processed";

            try (ByteArrayInputStream mdStream = new ByteArrayInputStream(chapter.getContent().getBytes())) {
                String mdUrl = ossUtil.uploadStream(mdStream, mdFileName, ossDir);
                chapter.setOssUrl(mdUrl);
                log.info("Uploaded chapter MD to OSS: {}", mdUrl);
            } catch (Exception e) {
                log.error("Failed to upload chapter MD: {}", mdFileName, e);
                throw new RuntimeException("章节MD上传OSS失败: " + chapter.getTitle(), e);
            }

            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setCourseId(courseId);
            doc.setTitle(chapter.getTitle());
            doc.setSourceType("讲义章节");
            doc.setFilePath(chapter.getOssUrl());
            doc.setCreatedAt(LocalDateTime.now());
            knowledgeDocumentMapper.insert(doc);
        }

        return chapters;
    }
}
