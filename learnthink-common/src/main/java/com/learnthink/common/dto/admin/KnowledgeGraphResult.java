package com.learnthink.common.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class KnowledgeGraphResult {
    private String courseId;
    private String courseName;
    private String textbookTitle;
    private Object graph;
    private List<String> searchQueries;
    private int ragSourcesCount;
    private String generatedAt;
}
