package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

/**
 * 辅导章节→知识点关联桥梁。
 * 对应表 tutoring_section_kp_links。
 */
@Data
@TableName("tutoring_section_kp_links")
public class TutoringSectionKpLink {
    private String id;
    private String sectionId;
    private String kpId;           // NULL = 课外
    private BigDecimal relevance;  // 0.00 ~ 1.00
}
