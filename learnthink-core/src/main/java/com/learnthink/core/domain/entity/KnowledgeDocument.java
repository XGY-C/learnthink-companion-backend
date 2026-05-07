package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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

    private LocalDateTime createdAt;
}
