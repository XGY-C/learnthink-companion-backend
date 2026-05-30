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

    /** AntV G6 知识图谱 JSON: {nodes: [...], edges: [...]} */
    private String knowledgeGraph;

    /** AI 生成的知识点树 JSON 快照: {name, kp_type, children:[...]} */
    private String kpTree;

    private LocalDateTime extractedAt;
}
