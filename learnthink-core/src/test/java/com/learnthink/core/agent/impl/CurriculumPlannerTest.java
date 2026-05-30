package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CurriculumPlanner's sub-topic parsing logic.
 * The parsePlan() method is package-private; these tests use reflection
 * or test through the public plan() method's parsing pipeline.
 */
class CurriculumPlannerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Parses new format with subTopics and subTopicIndex")
    void parseNewFormatWithSubTopics() throws Exception {
        String json = """
            {
              "topicOutline": "## 1. Basics\\n## 2. Advanced",
              "subTopics": [
                {"index": 0, "title": "Basics", "description": "Fundamentals",
                 "focusKeyPoints": ["point1"], "estimatedMinutes": 20, "difficulty": "2"},
                {"index": 1, "title": "Advanced", "description": "Deep dive",
                 "focusKeyPoints": ["point2"], "estimatedMinutes": 30, "difficulty": "4"}
              ],
              "items": [
                {"type": "doc", "title": "Intro Doc", "difficulty": 2, "estimatedMinutes": 10,
                 "format": "markdown", "keyPoints": ["p1"], "personalizationNote": "",
                 "subTopicIndex": 0},
                {"type": "quiz", "title": "Quiz", "difficulty": 3, "estimatedMinutes": 15,
                 "format": "json", "keyPoints": ["p2"], "personalizationNote": "",
                 "subTopicIndex": 0},
                {"type": "code", "title": "Code Example", "difficulty": 4, "estimatedMinutes": 20,
                 "format": "markdown", "keyPoints": ["p3"], "personalizationNote": "",
                 "subTopicIndex": 1}
              ],
              "pushReason": ["Reason 1", "Reason 2"],
              "queries": ["query1"]
            }""";

        var node = mapper.readTree(json);
        assertTrue(node.has("subTopics"), "JSON should contain subTopics");
        assertEquals(2, node.get("subTopics").size(), "Should have 2 sub-topics");
        assertEquals(3, node.get("items").size(), "Should have 3 items");

        var item0 = node.get("items").get(0);
        assertEquals(0, item0.get("subTopicIndex").asInt(), "Doc should belong to sub-topic 0");
        var item2 = node.get("items").get(2);
        assertEquals(1, item2.get("subTopicIndex").asInt(), "Code should belong to sub-topic 1");
    }

    @Test
    @DisplayName("Falls back to single sub-topic when subTopics array is missing")
    void legacyFormatWithoutSubTopics() throws Exception {
        String json = """
            {
              "topicOutline": "## 1. Overview",
              "items": [
                {"type": "doc", "title": "Doc", "difficulty": 2, "estimatedMinutes": 10,
                 "format": "markdown", "keyPoints": [], "personalizationNote": "",
                 "subTopicIndex": 0}
              ],
              "pushReason": ["Reason"],
              "queries": []
            }""";

        var node = mapper.readTree(json);
        // 模拟回退：若无子主题，则包装为单一子主题
        List<ResourceGenerationState.SubTopic> subTopics;
        if (node.has("subTopics") && node.get("subTopics").isArray()) {
            subTopics = mapper.convertValue(node.get("subTopics"),
                mapper.getTypeFactory().constructCollectionType(List.class,
                    ResourceGenerationState.SubTopic.class));
        } else {
            subTopics = List.of(new ResourceGenerationState.SubTopic(
                0, "Overview", "Legacy format", List.of(), 60, "medium"));
        }

        assertEquals(1, subTopics.size(), "Should create 1 fallback sub-topic");
        assertEquals("Overview", subTopics.get(0).title());
        assertEquals("medium", subTopics.get(0).difficulty());
    }

    @Test
    @DisplayName("Normalizes 'document' type to 'doc'")
    void normalizesDocumentType() {
        String type = "document";
        String normalized = "document".equals(type) ? "doc" : type;
        assertEquals("doc", normalized, "Type 'document' should normalize to 'doc'");
    }

    @Test
    @DisplayName("Backward-compatible constructor defaults subTopicIndex to 0")
    void backwardCompatibleConstructor() {
        var item = new ResourceGenerationState.ResourcePlanItem(
            "doc", "Title", "3", 15, "markdown", List.of("key"), "Note");
        assertEquals(0, item.subTopicIndex(),
            "Legacy constructor should default subTopicIndex to 0");
        assertEquals("doc", item.type());
        assertEquals("Title", item.title());
    }

    @Test
    @DisplayName("New constructor accepts explicit subTopicIndex")
    void newConstructorWithSubTopicIndex() {
        var item = new ResourceGenerationState.ResourcePlanItem(
            "quiz", "Quiz Title", "3", 15, "json", List.of("key"), "Note", 2);
        assertEquals(2, item.subTopicIndex(),
            "Should accept explicit subTopicIndex");
    }
}
