package com.learnthink.core.directanswer.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

/**
 * DirectAnswer 知识点链接实体。
 * 对应表 direct_answer_kp_links。
 */
@Data
@TableName("direct_answer_kp_links")
public class DirectAnswerKpLink {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String sectionId;     // → direct_answer_sections.id
    private String kpId;          // → course_knowledge_points.id，NULL=课外
    private BigDecimal relevance; // 关联度 0.00-1.00
}
