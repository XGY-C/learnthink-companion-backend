package com.learnthink.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.domain.entity.ResourceItem;
import com.learnthink.core.repository.ResourceItemMapper;
import com.learnthink.core.agent.orchestration.TaskEventBroadcaster;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 视频渲染结果轮询器 — 在后台定时查询 Manim API 的渲染状态，
 * 渲染完成后回写 resource_items 表的 videoUrl。
 */
@Slf4j
@Component
public class VideoRenderPoller {

    private final RestTemplate restTemplate;
    private final ResourceItemMapper resourceItemMapper;
    private final TaskEventBroadcaster eventBroadcaster;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    @Value("${manim.video.api.base-url}")
    private String manimApiBaseUrl;

    public VideoRenderPoller(
            @Qualifier("manimRestTemplate") RestTemplate restTemplate,
            ResourceItemMapper resourceItemMapper,
            TaskEventBroadcaster eventBroadcaster) {
        this.restTemplate = restTemplate;
        this.resourceItemMapper = resourceItemMapper;
        this.eventBroadcaster = eventBroadcaster;
    }

    /**
     * 启动对指定视频渲染任务的轮询，完成后自动更新资源项。
     * 立即将资源状态标记为 rendering，前端可据此展示"视频渲染中..."。
     *
     * @param manimTaskId   Manim 渲染任务 ID
     * @param resourceItemId 对应的 resource_items 记录 ID
     * @param taskId        任务 ID，用于 SSE 事件广播
     */
    public void startPolling(String manimTaskId, String resourceItemId, String taskId) {
        log.info("启动视频渲染轮询: manimTaskId={}, resourceItemId={}, taskId={}", manimTaskId, resourceItemId, taskId);
        updateResourceItemStatus(resourceItemId, "rendering");
        scheduler.execute(() -> pollLoop(manimTaskId, resourceItemId, taskId, 0));
    }

    private void pollLoop(String manimTaskId, String resourceItemId, String taskId, int attempt) {
        if (attempt > 250) { // 最多轮询 250 次（约 42 分钟）
            log.warn("视频渲染轮询超时，转为按需查询: manimTaskId={}, resourceItemId={}",
                manimTaskId, resourceItemId);
            return; // 不标记 failed，由 lazy resolve 按需查询真实状态
        }

        try {
            String url = manimApiBaseUrl + "/v1/video/tasks/" + manimTaskId;
            var response = restTemplate.getForEntity(url, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.debug("查询任务状态失败，重试中: manimTaskId={}, status={}", manimTaskId, response.getStatusCode());
                scheduleRetry(manimTaskId, resourceItemId, taskId, attempt);
                return;
            }

            Map<String, Object> body = response.getBody();
            String status = extractStatus(body);
            log.debug("任务状态: manimTaskId={}, status={}, attempt={}", manimTaskId, status, attempt);

            if ("COMPLETED".equals(status)) {
                String videoUrl = extractVideoUrl(body);
                if (videoUrl != null) {
                    updateResourceItemVideoUrl(resourceItemId, videoUrl);
                    broadcastVideoReady(resourceItemId, taskId);
                    log.info("视频URL已更新到数据库: resourceItemId={}, videoUrl={}", resourceItemId, videoUrl);
                } else {
                    log.warn("任务完成但未找到videoUrl: manimTaskId={}", manimTaskId);
                    updateResourceItemFailed(resourceItemId);
                    broadcastVideoFailed(resourceItemId, taskId);
                }
            } else if ("FAILED".equals(status)) {
                log.warn("视频渲染失败: manimTaskId={}, resourceItemId={}", manimTaskId, resourceItemId);
                updateResourceItemFailed(resourceItemId);
                broadcastVideoFailed(resourceItemId, taskId);
            } else {
                scheduleRetry(manimTaskId, resourceItemId, taskId, attempt);
            }
        } catch (Exception e) {
            log.warn("轮询异常，重试中: manimTaskId={}, error={}", manimTaskId, e.getMessage());
            scheduleRetry(manimTaskId, resourceItemId, taskId, attempt);
        }
    }

    private void scheduleRetry(String manimTaskId, String resourceItemId, String taskId, int attempt) {
        scheduler.schedule(
            () -> pollLoop(manimTaskId, resourceItemId, taskId, attempt + 1),
            10, TimeUnit.SECONDS
        );
    }

    private String extractStatus(Map<String, Object> body) {
        try {
            Object task = body.get("task");
            if (task instanceof Map<?, ?>) {
                Object status = ((Map<?, ?>) task).get("status");
                return status != null ? status.toString() : "";
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * 按需解析视频 URL — 当用户查看资源时调用。
     * 检查 Manim API 的任务状态，如果已完成则更新数据库并返回 URL。
     *
     * @return 视频 URL（未完成则返回 null）
     */
    public String resolveVideoUrl(ResourceItem item) {
        if (item == null || !"video".equals(item.getType())) return null;
        if ("failed".equals(item.getStatus())) return null;

        String metaJson = item.getMetadataJson();
        if (metaJson == null) return null;
        try {
            JsonNode meta = objectMapper.readTree(metaJson);
            String contentStr = meta.has("content") ? meta.get("content").asText() : "{}";
            JsonNode content = objectMapper.readTree(contentStr);
            if (content.has("videoUrl") && !content.get("videoUrl").isNull()
                && !content.get("videoUrl").asText().isBlank()) {
                return content.get("videoUrl").asText();
            }
            if (!content.has("manimTaskId")) return null;

            String manimTaskId = content.get("manimTaskId").asText();
            String url = manimApiBaseUrl + "/v1/video/tasks/" + manimTaskId;
            var response = restTemplate.getForEntity(url, Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) return null;

            String status = extractStatus(response.getBody());
            if ("COMPLETED".equals(status)) {
                String videoUrl = extractVideoUrl(response.getBody());
                if (videoUrl != null) {
                    updateResourceItemVideoUrl(item.getId(), videoUrl);
                    return videoUrl;
                }
            } else if ("FAILED".equals(status)) {
                updateResourceItemFailed(item.getId());
            }
        } catch (Exception e) {
            log.debug("按需解析 videoUrl 失败: itemId={}, error={}", item.getId(), e.getMessage());
        }
        return null;
    }

    private String extractVideoUrl(Map<String, Object> body) {
        try {
            Object result = body.get("result");
            if (result instanceof Map) {
                return String.valueOf(((Map<?, ?>) result).getOrDefault("videoUrl", null));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void updateResourceItemVideoUrl(String resourceItemId, String videoUrl) {
        ResourceItem item = resourceItemMapper.selectById(resourceItemId);
        if (item == null) return;

        try {
            String metaJson = item.getMetadataJson();
            JsonNode meta = objectMapper.readTree(metaJson != null ? metaJson : "{}");
            String contentStr = meta.has("content") ? meta.get("content").asText() : "{}";
            JsonNode content = objectMapper.readTree(contentStr);
            var updatedContent = ((com.fasterxml.jackson.databind.node.ObjectNode) content)
                .put("videoUrl", videoUrl);
            var updatedMeta = ((com.fasterxml.jackson.databind.node.ObjectNode) meta)
                .put("content", objectMapper.writeValueAsString(updatedContent));
            item.setMetadataJson(objectMapper.writeValueAsString(updatedMeta));
            item.setStatus("ready");
            item.setUpdatedAt(LocalDateTime.now());
            resourceItemMapper.updateById(item);
        } catch (Exception e) {
            log.error("更新resourceItem videoUrl失败: {}", e.getMessage(), e);
        }
    }

    private void updateResourceItemFailed(String resourceItemId) {
        updateResourceItemStatus(resourceItemId, "failed");
    }

    private void updateResourceItemStatus(String resourceItemId, String status) {
        ResourceItem item = resourceItemMapper.selectById(resourceItemId);
        if (item == null) return;
        item.setStatus(status);
        item.setUpdatedAt(LocalDateTime.now());
        resourceItemMapper.updateById(item);
    }

    private void broadcastVideoReady(String resourceItemId, String taskId) {
        try {
            ResourceItem item = resourceItemMapper.selectById(resourceItemId);
            if (item != null && eventBroadcaster != null) {
                eventBroadcaster.broadcastEvent(taskId, "checklist.video.ready",
                    Map.of("type", "video", "title", item.getTitle() != null ? item.getTitle() : "视频资源"));
            }
        } catch (Exception e) {
            log.warn("广播视频就绪事件失败: {}", e.getMessage());
        }
    }

    private void broadcastVideoFailed(String resourceItemId, String taskId) {
        try {
            ResourceItem item = resourceItemMapper.selectById(resourceItemId);
            if (item != null && eventBroadcaster != null) {
                eventBroadcaster.broadcastEvent(taskId, "checklist.video.failed",
                    Map.of("type", "video", "title", item.getTitle() != null ? item.getTitle() : "视频资源"));
            }
        } catch (Exception e) {
            log.warn("广播视频失败事件失败: {}", e.getMessage());
        }
    }
}
