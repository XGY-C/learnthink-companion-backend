package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 书籍基本信息 — 从教材MD中提取的作者、简介、目录.
 */
@Data
@TableName("book_info")
public class BookInfo {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String documentId;

    private String title;

    private String author;

    private String introduction;

    /** JSON: [{"title":"第1章 xxx","chapterIndex":1}, ...] */
    private String toc;

    private LocalDateTime extractedAt;
}
