package com.learnthink.core.service;

import com.learnthink.common.dto.CodeRunResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

@Component
public class Judge0Client {

    private static final Logger log = LoggerFactory.getLogger(Judge0Client.class);

    @Value("${judge0.base-url:http://localhost:2358}")
    private String baseUrl;

    @Value("${judge0.authn-token:}")
    private String authnToken;

    @Value("${judge0.authz-token:}")
    private String authzToken;

    private final ObjectMapper mapper = new ObjectMapper();

    public CodeRunResult submit(String sourceCode, int languageId, String stdin,
                                int cpuTimeLimit, int memoryLimit) {
        long startTime = System.currentTimeMillis();
        String urlStr = baseUrl + "/submissions?wait=true&base64_encoded=true";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_code", Base64.getEncoder().encodeToString(sourceCode.getBytes(StandardCharsets.UTF_8)));
        body.put("language_id", languageId);
        body.put("stdin", stdin != null ? Base64.getEncoder().encodeToString(stdin.getBytes(StandardCharsets.UTF_8)) : "");
        body.put("cpu_time_limit", cpuTimeLimit);
        body.put("memory_limit", memoryLimit);

        log.info("[JUDGE0] ========== 提交开始 ==========");
        log.info("[JUDGE0] 目标URL: {}", urlStr);
        log.info("[JUDGE0] 参数: languageId={}, codeLen={}, stdin={}, cpuTimeLimit={}s, memoryLimit={}MB",
                languageId, sourceCode.length(),
                stdin != null ? ("len=" + stdin.length()) : "null",
                cpuTimeLimit, memoryLimit / 1024);
        log.info("[JUDGE0] 认证: authnToken={}, authzToken={}",
                authnToken != null && !authnToken.isBlank() ? "***" : "未配置",
                authzToken != null && !authzToken.isBlank() ? "***" : "未配置");

        try {
            String jsonBody = mapper.writeValueAsString(body);
            log.debug("[JUDGE0] 请求体(JSON): len={}", jsonBody.length());
            if (log.isDebugEnabled() && jsonBody.length() > 1000) {
                log.debug("[JUDGE0] 请求体预览(前1000字符): {}", jsonBody.substring(0, 1000) + "...");
            }

            log.info("[JUDGE0] 建立连接...");
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (authnToken != null && !authnToken.isBlank()) {
                conn.setRequestProperty("X-Auth-Token", authnToken);
            }
            if (authzToken != null && !authzToken.isBlank()) {
                conn.setRequestProperty("X-Judge0-User", authzToken);
            }

            log.info("[JUDGE0] 发送请求...");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            log.info("[JUDGE0] 等待响应...");
            int responseCode = conn.getResponseCode();
            long networkTime = System.currentTimeMillis() - startTime;
            log.info("[JUDGE0] HTTP响应码: {}, 网络耗时={}ms", responseCode, networkTime);

            String responseBody;
            if (responseCode >= 200 && responseCode < 300) {
                try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8.name())) {
                    responseBody = scanner.useDelimiter("\\A").next();
                }
                log.info("[JUDGE0] 响应成功, bodyLen={}", responseBody.length());
            } else {
                try (Scanner scanner = new Scanner(conn.getErrorStream(), StandardCharsets.UTF_8.name())) {
                    responseBody = scanner.useDelimiter("\\A").next();
                }
                log.error("[JUDGE0] HTTP错误: {} - {}", responseCode, responseBody);
                return CodeRunResult.builder()
                    .stderr("Judge0 HTTP error: " + responseCode + " - " + responseBody)
                    .status("HTTP Error")
                    .statusCode(12)
                    .build();
            }

            if (log.isDebugEnabled()) {
                log.debug("[JUDGE0] 响应体: {}", responseBody);
            }

            log.info("[JUDGE0] 解析响应...");
            JsonNode node = mapper.readTree(responseBody);

            // wait=true 未生效时 Judge0 只返回 token，需要轮询获取完整结果
            if (!node.has("status") && node.has("token")) {
                String token = node.get("token").asText();
                log.warn("[JUDGE0] wait=true 未返回完整结果, 收到 token={}, 开始轮询...", token);
                return pollSubmission(token, startTime);
            }

            // 响应体无 status 也无 token，记录便于排查
            if (!node.has("status")) {
                log.warn("[JUDGE0] 响应体无 status 字段, body={}", responseBody);
            }

            CodeRunResult result = parseResult(node);
            long totalTime = System.currentTimeMillis() - startTime;
            log.info("[JUDGE0] ========== 提交结束 ==========");
            logResult(result, totalTime);
            return result;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[JUDGE0] ========== 提交异常 ==========");
            log.error("[JUDGE0] 异常类型: {}, 消息: {}", e.getClass().getSimpleName(), e.getMessage());
            log.error("[JUDGE0] 异常堆栈:", e);
            log.error("[JUDGE0] 总耗时={}ms", elapsed);
            return CodeRunResult.builder()
                .stderr("Judge0 service error: " + e.getMessage())
                .status("Internal Error")
                .statusCode(12)
                .build();
        }
    }

    /**
     * 轮询 Judge0 获取提交结果（当 wait=true 未返回完整结果时使用）。
     * 通过 token 反复 GET /submissions/{token} 直到获得终态状态。
     */
    private CodeRunResult pollSubmission(String token, long startTime) {
        String pollUrl = baseUrl + "/submissions/" + token + "?base64_encoded=true";
        int maxAttempts = 30;
        long pollIntervalMs = 500;

        for (int i = 1; i <= maxAttempts; i++) {
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[JUDGE0] 轮询被中断");
                break;
            }

            try {
                log.info("[JUDGE0] 轮询第 {}/{} 次, url={}", i, maxAttempts, pollUrl);
                URL url = new URL(pollUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                if (authnToken != null && !authnToken.isBlank()) {
                    conn.setRequestProperty("X-Auth-Token", authnToken);
                }
                if (authzToken != null && !authzToken.isBlank()) {
                    conn.setRequestProperty("X-Judge0-User", authzToken);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    String errBody = "";
                    try (Scanner scanner = new Scanner(conn.getErrorStream(), StandardCharsets.UTF_8.name())) {
                        errBody = scanner.useDelimiter("\\A").next();
                    } catch (Exception ignored) {}
                    log.warn("[JUDGE0] 轮询 HTTP {} - body={}", responseCode, errBody);
                    continue;
                }

                String pollBody;
                try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8.name())) {
                    pollBody = scanner.useDelimiter("\\A").next();
                }

                JsonNode node = mapper.readTree(pollBody);
                if (!node.has("status")) {
                    log.debug("[JUDGE0] 轮询响应仍无 status, 继续...");
                    continue;
                }

                int statusCode = node.get("status").has("id")
                    ? node.get("status").get("id").asInt() : 0;

                // status <= 2: In Queue(1) / Processing(2)，继续轮询
                if (statusCode <= 2) {
                    String desc = node.get("status").has("description")
                        ? node.get("status").get("description").asText() : "Unknown";
                    log.info("[JUDGE0] 轮询 status='{}' (id={}), 继续等待...", desc, statusCode);
                    continue;
                }

                // 已到达终态（status >= 3），解析并返回
                CodeRunResult result = parseResult(node);
                long totalTime = System.currentTimeMillis() - startTime;
                log.info("[JUDGE0] ========== 提交结束（轮询） ==========");
                logResult(result, totalTime);
                return result;

            } catch (Exception e) {
                log.warn("[JUDGE0] 轮询异常: {}", e.getMessage());
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        log.error("[JUDGE0] 轮询超时, 未获取结果, 总耗时={}ms", totalTime);
        return CodeRunResult.builder()
            .stderr("Judge0 轮询超时，未获取执行结果")
            .status("Poll Timeout")
            .statusCode(13)
            .build();
    }

    /**
     * 将 Judge0 响应 JSON 解析为 CodeRunResult。
     * 当 base64_encoded=true 时，stdout/stderr/compile_output 需要 base64 解码。
     */
    private CodeRunResult parseResult(JsonNode node) {
        String statusDesc = node.has("status") && node.get("status").has("description")
            ? node.get("status").get("description").asText() : "Unknown";
        int statusCode = node.has("status") && node.get("status").has("id")
            ? node.get("status").get("id").asInt() : 0;

        String stdout = decodeBase64Field(node, "stdout");
        String stderr = decodeBase64Field(node, "stderr");
        String compileOutput = decodeBase64Field(node, "compile_output");
        Double time = node.has("time") && !node.get("time").isNull()
            ? node.get("time").asDouble() : null;
        Double memory = node.has("memory") && !node.get("memory").isNull()
            ? node.get("memory").asDouble() : null;

        return CodeRunResult.builder()
            .stdout(stdout)
            .stderr(stderr)
            .compileOutput(compileOutput)
            .status(statusDesc)
            .statusCode(statusCode)
            .time(time)
            .memory(memory)
            .build();
    }

    /**
     * 从 JsonNode 中读取并 base64 解码指定字段。
     */
    private String decodeBase64Field(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String raw = node.get(field).asText();
        try {
            // 使用 MIME 解码器，兼容 base64 文本中的换行符
            byte[] decoded = Base64.getMimeDecoder().decode(raw);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 如果不是 base64（例如 base64_encoded=false 的响应），直接返回原文
            return raw;
        }
    }

    /**
     * 统一输出结果日志。
     */
    private void logResult(CodeRunResult result, long totalTimeMs) {
        log.info("[JUDGE0] 解析结果: statusCode={}, status='{}', time={}s, memory={}KB, stdout={}, stderr={}, compileOutput={}",
                result.getStatusCode(), result.getStatus(), result.getTime(),
                result.getMemory() != null ? result.getMemory() / 1024 : null,
                result.getStdout() != null ? "len=" + result.getStdout().length() : "null",
                result.getStderr() != null ? "len=" + result.getStderr().length() : "null",
                result.getCompileOutput() != null ? "len=" + result.getCompileOutput().length() : "null");
        log.info("[JUDGE0] 总耗时={}ms", totalTimeMs);
    }
}
