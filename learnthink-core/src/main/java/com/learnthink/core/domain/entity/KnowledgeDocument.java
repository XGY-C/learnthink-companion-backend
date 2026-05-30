package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 知识库元数据（最小实现）
 */
@Data
@TableName("knowledge_documents")
public class KnowledgeDocument {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String courseId;

    private String title;

    /**
     * 讲义/术语/题库/阅读
     */
    private String sourceType;

    private String filePath;

    /** 父文档ID（讲义章节 → 教材/讲义） */
    private String parentId;

    /** SHA-256 文件哈希，用于增量索引去重 */
    @TableField("doc_hash")
    private String docHash;

    /** 切分后的 chunk 数量 */
    @TableField("chunk_count")
    private Integer chunkCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
