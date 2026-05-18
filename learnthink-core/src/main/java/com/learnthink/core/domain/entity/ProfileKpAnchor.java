package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 画像维度标签 → 课程知识点的锚定记录
 */
@Data
@TableName("profile_kp_anchors")
public class ProfileKpAnchor {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String profileVersionId;

    private String kpId;

    private String dimensionKey;   // knowledge_basis / interest_direction / error_pattern

    private String relationType;   // strong / weak / interest / error_prone

    private String scopeAtAnchor;  // core_curriculum / prerequisite / supplementary / extracurricular

    private BigDecimal confidence;

    private String source;         // explicit / inferred / quiz_result

    private String matchMethod;    // fuzzy / embedding / llm_direct / keyword

    private String labelText;      // 画像原文中的标签文本

    private LocalDateTime createdAt;
}
