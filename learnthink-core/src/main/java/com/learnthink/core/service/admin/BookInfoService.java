package com.learnthink.core.service.admin;

import com.learnthink.core.domain.entity.BookInfo;

/**
 * 书籍基本信息提取/查询服务.
 */
public interface BookInfoService {

    /** 查询文档的书籍信息，未提取则返回null */
    BookInfo getByDocumentId(String documentId);

    /** 从Markdown内容中提取并保存书籍信息（自动解析作者、简介、目录） */
    BookInfo extract(String documentId, String fullMarkdown);

    /** 从OSS下载MD后提取并保存 */
    BookInfo extractFromOss(String documentId);

    /** 手动更新书籍信息（作者、简介等） */
    BookInfo update(String documentId, String author, String introduction);
}
