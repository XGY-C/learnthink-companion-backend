package com.learnthink.core.service.admin;

import com.learnthink.common.dto.admin.KnowledgeGraphResult;

import java.util.Map;

public interface KnowledgeGraphService {
    KnowledgeGraphResult generate(String courseId);
    KnowledgeGraphResult get(String courseId);
    void save(String courseId, Map<String, Object> graphData);

    /** 生成知识点树（复用 KG 流程的 RAG 检索，最后一步输出树结构而非图） */
    KnowledgeGraphResult generateKpTree(String courseId);
}
