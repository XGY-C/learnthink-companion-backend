package com.learnthink.core.agent.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时任务工具
 * <p>创建、列出、取消定时学习提醒任务。支持三种调度模式：
 * at（一次性）、schedule（重复间隔）、cron（cron 表达式）。</p>
 *
 * <h3>调度模式</h3>
 * <ul>
 *   <li>{@code at} — 在指定时间执行一次（如"明天 9 点提醒复习"）</li>
 *   <li>{@code schedule} — 按固定间隔重复执行（如"每 2 小时提醒"）</li>
 *   <li>{@code cron} — 按 cron 表达式重复执行（如"每周一 9 点"）</li>
 *   <li>{@code list} — 列出当前用户的定时任务</li>
 *   <li>{@code cancel} — 取消指定任务</li>
 * </ul>
 *
 * <h3>接入说明</h3>
 * <p>当前任务存储在内存 Map 中。接入真实定时框架（如 Spring @Scheduled、Quartz）后，
 * 替换 {@link #taskStore} 为持久化存储，并在 {@link #scheduleTask} 中注册实际调度。</p>
 */
public class CronTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(CronTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 内存任务存储——接入 Quartz/数据库后替换 */
    private final Map<String, Map<String, Object>> taskStore = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "cron_tool";
    }

    @Override
    public String description() {
        return "创建、列出或取消定时学习提醒任务。" +
               "支持三种调度模式：at（指定时间一次性）、schedule（固定间隔重复）、cron（cron表达式重复）。" +
               "用于为学生创建复习提醒、作业截止提醒等。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "description": "操作类型",
                  "enum": ["at", "schedule", "cron", "list", "cancel"]
                },
                "message": {
                  "type": "string",
                  "description": "提醒消息内容（at/schedule/cron 模式使用）"
                },
                "run_at": {
                  "type": "string",
                  "description": "at 模式：执行时间（ISO 8601 格式，如 2025-12-25T09:00:00）"
                },
                "every_seconds": {
                  "type": "integer",
                  "description": "schedule 模式：重复间隔（秒），最小 30",
                  "default": 3600
                },
                "cron_expr": {
                  "type": "string",
                  "description": "cron 模式：5 字段 cron 表达式，如 '0 9 * * 1-5'（工作日9点）"
                },
                "tz": {
                  "type": "string",
                  "description": "cron 模式：时区，如 'Asia/Shanghai'",
                  "default": "Asia/Shanghai"
                },
                "job_id": {
                  "type": "string",
                  "description": "cancel 模式：要取消的任务 ID"
                }
              },
              "required": ["action"],
              "additionalProperties": false
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = MAPPER.readValue(jsonArgs, Map.class);
            String action = String.valueOf(args.getOrDefault("action", "")).trim();
            String userId = String.valueOf(args.getOrDefault("user_id", "anonymous"));

            log.info("CronTool: action={}, userId={}", action, userId);

            return switch (action) {
                case "at" -> handleAt(args, userId);
                case "schedule" -> handleSchedule(args, userId);
                case "cron" -> handleCron(args, userId);
                case "list" -> handleList(userId);
                case "cancel" -> handleCancel(args);
                default -> errorJson("未知操作: " + action + "，支持：at, schedule, cron, list, cancel");
            };

        } catch (Exception e) {
            log.error("CronTool failed", e);
            return errorJson("定时任务操作失败: " + e.getMessage());
        }
    }

    /** at 模式：一次性定时任务 */
    private String handleAt(Map<String, Object> args, String userId) {
        String message = String.valueOf(args.getOrDefault("message", "")).trim();
        String runAt = String.valueOf(args.getOrDefault("run_at", "")).trim();
        if (message.isEmpty() || runAt.isEmpty()) {
            return errorJson("at 模式需要 message 和 run_at 参数");
        }
        return scheduleTask(userId, "at", message, Map.of("run_at", runAt));
    }

    /** schedule 模式：固定间隔重复 */
    private String handleSchedule(Map<String, Object> args, String userId) {
        String message = String.valueOf(args.getOrDefault("message", "")).trim();
        int everySeconds = args.containsKey("every_seconds")
                ? Math.max(((Number) args.get("every_seconds")).intValue(), 30)
                : 3600;
        if (message.isEmpty()) {
            return errorJson("schedule 模式需要 message 参数");
        }
        return scheduleTask(userId, "schedule", message, Map.of("every_seconds", everySeconds));
    }

    /** cron 模式：cron 表达式重复 */
    private String handleCron(Map<String, Object> args, String userId) {
        String message = String.valueOf(args.getOrDefault("message", "")).trim();
        String cronExpr = String.valueOf(args.getOrDefault("cron_expr", "")).trim();
        String tz = String.valueOf(args.getOrDefault("tz", "Asia/Shanghai"));
        if (message.isEmpty() || cronExpr.isEmpty()) {
            return errorJson("cron 模式需要 message 和 cron_expr 参数");
        }
        return scheduleTask(userId, "cron", message, Map.of("cron_expr", cronExpr, "tz", tz));
    }

    /** list 模式：列出用户的任务 */
    private String handleList(String userId) {
        List<Map<String, Object>> userTasks = taskStore.values().stream()
                .filter(t -> userId.equals(t.get("user_id")))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "list");
        result.put("tasks", userTasks);
        result.put("count", userTasks.size());
        result.put("success", true);
        return toJson(result);
    }

    /** cancel 模式：取消任务 */
    private String handleCancel(Map<String, Object> args) {
        String jobId = String.valueOf(args.getOrDefault("job_id", "")).trim();
        if (jobId.isEmpty()) {
            return errorJson("cancel 模式需要 job_id 参数");
        }
        Map<String, Object> removed = taskStore.remove(jobId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "cancel");
        result.put("job_id", jobId);
        result.put("success", removed != null);
        result.put("message", removed != null ? "任务已取消" : "任务不存在");
        return toJson(result);
    }

    /** 创建定时任务并存储 */
    private String scheduleTask(String userId, String type, String message, Map<String, Object> schedule) {
        String jobId = UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("job_id", jobId);
        task.put("user_id", userId);
        task.put("type", type);
        task.put("message", message);
        task.put("schedule", schedule);
        task.put("status", "active");
        task.put("created_at", java.time.Instant.now().toString());

        taskStore.put(jobId, task);

        // ================================================================
        // 接入真实调度框架时，在此注册实际调度：
        //   - Spring @Scheduled: 不支持动态注册，需用 TaskScheduler
        //   - Quartz: scheduler.scheduleJob(job, trigger)
        //   - 通知：结合 NotificationService 推送提醒
        // ================================================================

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", type);
        result.put("job_id", jobId);
        result.put("status", "active");
        result.put("message", "定时任务已创建");
        result.put("success", true);
        return toJson(result);
    }

    private String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"error\":\"JSON 序列化失败\"}";
        }
    }

    private String errorJson(String message) {
        return toJson(Map.of("error", message, "success", false));
    }
}
