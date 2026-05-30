package com.learnthink.core.agent.manager;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用于资源生成的结构化清单。
 * 由 ResourceChecklistPlanner 创建，由 AgentManager 消费，
 * 在整个生成生命周期中被跟踪和更新。
 */
public class GenerationChecklist {

    private final String taskId;
    private final String topic;
    private final List<ChecklistItem> items;
    private final Map<String, ItemStatus> statusMap;
    private final Instant createdAt;

    public GenerationChecklist(String taskId, String topic, List<ChecklistItem> items) {
        this.taskId = taskId;
        this.topic = topic;
        this.items = List.copyOf(items);
        this.statusMap = new ConcurrentHashMap<>();
        this.createdAt = Instant.now();
        for (var item : items) {
            statusMap.put(item.title(), ItemStatus.PENDING);
        }
    }

    public void markGenerating(String title) { statusMap.put(title, ItemStatus.GENERATING); }
    public void markDone(String title) { statusMap.put(title, ItemStatus.DONE); }
    public void markFailed(String title) { statusMap.put(title, ItemStatus.FAILED); }
    public ItemStatus status(String title) { return statusMap.getOrDefault(title, ItemStatus.PENDING); }

    public int doneCount() { return count(ItemStatus.DONE); }
    public int failedCount() { return count(ItemStatus.FAILED); }
    public int generatingCount() { return count(ItemStatus.GENERATING); }
    public int pendingCount() { return totalCount() - doneCount() - failedCount() - generatingCount(); }
    public int totalCount() { return items.size(); }

    public double progressPercent() {
        if (items.isEmpty()) return 100.0;
        return (doneCount() + failedCount()) * 100.0 / items.size();
    }

    public boolean isComplete() {
        return doneCount() + failedCount() == items.size();
    }

    public List<ChecklistItem> pendingItems() {
        return items.stream().filter(i -> status(i.title()) == ItemStatus.PENDING).toList();
    }

    public List<ChecklistItem> generatingItems() {
        return items.stream().filter(i -> status(i.title()) == ItemStatus.GENERATING).toList();
    }

    private int count(ItemStatus s) {
        return (int) statusMap.values().stream().filter(v -> v == s).count();
    }

    // ---- 前端序列化 ----

    public Map<String, Object> toFrontendFormat() {
        List<Map<String, Object>> itemList = new ArrayList<>();
        for (var item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", item.type()); m.put("title", item.title());
            m.put("description", item.description()); m.put("difficulty", item.difficulty());
            m.put("estimatedMinutes", item.estimatedMinutes()); m.put("format", item.format());
            m.put("keyPoints", item.keyPoints()); m.put("priority", item.priority());
            m.put("status", status(item.title()).name().toLowerCase());
            itemList.add(m);
        }
        return Map.of("taskId", taskId, "topic", topic,
            "totalCount", totalCount(), "doneCount", doneCount(),
            "failedCount", failedCount(), "pendingCount", pendingCount(),
            "generatingCount", generatingCount(), "progressPercent", progressPercent(),
            "items", itemList, "createdAt", createdAt.toString());
    }

    // ---- 类型定义 ----

    public record ChecklistItem(
        String type, String title, String description,
        String difficulty, int estimatedMinutes, String format,
        List<String> keyPoints, String personalizationNote, int priority
    ) {}

    public enum ItemStatus { PENDING, GENERATING, DONE, FAILED }

    public String taskId() { return taskId; }
    public String topic() { return topic; }
    public List<ChecklistItem> items() { return items; }
}
