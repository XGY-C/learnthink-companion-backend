package com.learnthink.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.domain.entity.CourseKnowledgePoint;
import com.learnthink.core.repository.CourseKnowledgePointMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Extracts a course knowledge point tree from knowledge documents using LLM.
 * This is a utility for bootstrapping the KP tree — run once per course.
 */
@Service
public class KpExtractionService {

    private static final Logger log = LoggerFactory.getLogger(KpExtractionService.class);
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final CourseKnowledgePointMapper kpMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KpExtractionService(@Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder,
                               PromptLoader promptLoader,
                               CourseKnowledgePointMapper kpMapper) {
        this.chatClient = chatClientBuilder.build();
        this.promptLoader = promptLoader;
        this.kpMapper = kpMapper;
    }

    /**
     * Extract the KP tree from a course's knowledge documents.
     *
     * @param courseId the course to build the KP tree for
     * @param courseName the course name for context
     * @param documentSummaries brief descriptions of available knowledge documents
     * @return the root KP node
     */
    public CourseKnowledgePoint extractTree(String courseId, String courseName,
                                             List<String> documentSummaries) {
        log.info("Extracting KP tree for course: {} ({} documents)", courseName, documentSummaries.size());

        String docsText = String.join("\n", documentSummaries.stream()
            .map(s -> "- " + s)
            .toList());

        String systemPrompt = promptLoader.get("kp/extract_tree");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = buildDefaultExtractPrompt();
        }

        String userPrompt = String.format("""
            Course: %s

            Available documents and materials:
            %s

            Please extract the knowledge point tree structure.
            """, courseName, docsText);

        try {
            String response = chatClient.prompt()
                .messages(new SystemMessage(systemPrompt), new UserMessage(userPrompt))
                .call()
                .content();

            return parseAndSaveTree(response, courseId);
        } catch (Exception e) {
            log.error("Failed to extract KP tree for course {}: {}", courseId, e.getMessage(), e);
            throw new RuntimeException("KP tree extraction failed: " + e.getMessage(), e);
        }
    }

    private CourseKnowledgePoint parseAndSaveTree(String json, String courseId) {
        try {
            String cleaned = json;
            if (cleaned.contains("```")) {
                cleaned = cleaned.substring(cleaned.indexOf("{"), cleaned.lastIndexOf("}") + 1);
            }

            Map<String, Object> tree = objectMapper.readValue(cleaned,
                new TypeReference<Map<String, Object>>() {});

            return saveNode(tree, null, courseId, 0);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse KP tree JSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private CourseKnowledgePoint saveNode(Map<String, Object> node, String parentId,
                                           String courseId, int depth) {
        CourseKnowledgePoint kp = new CourseKnowledgePoint();
        kp.setCourseId(courseId);
        kp.setParentId(parentId);
        kp.setName((String) node.getOrDefault("name", "Unnamed"));
        kp.setKpType((String) node.getOrDefault("kp_type", "concept"));
        kp.setScope((String) node.getOrDefault("scope", "core_curriculum"));
        kp.setDepth(depth);
        kp.setSortOrder(((Number) node.getOrDefault("sort_order", 0)).intValue());
        kp.setDescription((String) node.get("description"));

        try {
            Object objectives = node.get("learning_objectives");
            kp.setLearningObjectives(objectives != null ? objectMapper.writeValueAsString(objectives) : null);
            Object prereqs = node.get("prerequisite_kps");
            kp.setPrerequisiteKps(prereqs != null ? objectMapper.writeValueAsString(prereqs) : null);
            Object related = node.get("related_kps");
            kp.setRelatedKps(related != null ? objectMapper.writeValueAsString(related) : null);
            Object keywords = node.get("keywords");
            kp.setKeywords(keywords != null ? objectMapper.writeValueAsString(keywords) : null);
        } catch (Exception e) {
            log.warn("Failed to serialize JSON fields for KP {}: {}", kp.getName(), e.getMessage());
        }

        Object diff = node.get("difficulty");
        kp.setDifficulty(diff != null ? ((Number) diff).intValue() : 3);
        Object estMin = node.get("estimated_minutes");
        kp.setEstimatedMinutes(estMin != null ? ((Number) estMin).intValue() : null);

        kpMapper.insert(kp);
        log.debug("Saved KP: {} (depth={}, type={})", kp.getName(), depth, kp.getKpType());

        // Recursively save children
        List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
        if (children != null) {
            for (Map<String, Object> child : children) {
                saveNode(child, kp.getId(), courseId, depth + 1);
            }
        }

        return kp;
    }

    private String buildDefaultExtractPrompt() {
        return """
            You are a curriculum designer. Extract the knowledge point tree from the course materials.

            Output a strict JSON tree:
            {
              "name": "Course Name",
              "kp_type": "course",
              "scope": "core_curriculum",
              "description": "...",
              "children": [
                {
                  "name": "Chapter 1: ...",
                  "kp_type": "chapter",
                  "scope": "core_curriculum",
                  "sort_order": 1,
                  "children": [
                    {
                      "name": "Concept name",
                      "kp_type": "concept",
                      "scope": "core_curriculum",
                      "description": "...",
                      "learning_objectives": ["obj1", "obj2"],
                      "difficulty": 3,
                      "estimated_minutes": 30,
                      "keywords": ["keyword1", "keyword2"],
                      "children": []
                    }
                  ]
                }
              ]
            }

            Rules:
            - scope values: core_curriculum (in syllabus), prerequisite (should be learned before), supplementary (nice to know)
            - kp_type values: course, chapter, section, concept, skill
            - Every concept must have keywords for fuzzy matching
            - Chapter name format: "第N章 ChapterTitle" or "ChapterTitle"
            """;
    }
}
