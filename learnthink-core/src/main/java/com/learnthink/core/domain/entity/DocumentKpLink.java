package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

/**
 * 知识文档 → 知识点的关联（多对多）
 */
@Data
@TableName("document_kp_links")
public class DocumentKpLink {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String docId;

    private String kpId;

    private BigDecimal relevance;  // 该文档对此KP的核心程度，默认1.0
}
