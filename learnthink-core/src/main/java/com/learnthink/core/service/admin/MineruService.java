package com.learnthink.core.service.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Mineru精准解析API客户端 (单文件URL模式, vlm模型).
 *
 * <p>流程: POST /api/v4/extract/task → 轮询 GET /api/v4/extract/task/{task_id}
 * → done → 下载zip包 → 解压提取full.md</p>
 *
 * <p>限制: ≤200MB, ≤200页 (对比轻量API的10MB/20页)</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MineruService {

    private final RestTemplate mineruRestTemplate;

    private static final long POLL_INTERVAL_MS = 3000;
    private static final long POLL_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int MAX_RETRIES = 3;

    /**
     * 提交PDF URL给Mineru精准解析，轮询等待完成，返回full.md文本内容.
     *
     * @param fileUrl 可公网访问/OSS签名PDF URL
     * @return 完整Markdown文本
     */
    public String parseDocument(String fileUrl) {
        String taskId = submitTask(fileUrl);
        log.info("Mineru task submitted, taskId: {}", taskId);

        byte[] zipBytes = pollResult(taskId);
        log.info("Mineru task done, downloaded zip ({} bytes)", zipBytes.length);

        return extractMarkdownFromZip(zipBytes);
    }

    private String submitTask(String fileUrl) {
        Map<String, Object> request = new HashMap<>();
        request.put("url", fileUrl);

        // 精准API推荐使用vlm模型，支持公式/表格识别
        request.put("model_version", "vlm");
        request.put("language", "ch");
        request.put("enable_table", true);
        request.put("enable_formula", true);
        request.put("is_ocr", false);

        Exception lastEx = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("Submitting Mineru extract task (attempt {}/{}): {}",
                        attempt + 1, MAX_RETRIES + 1, fileUrl);

                Map<String, Object> resp = mineruRestTemplate.postForObject(
                        "/api/v4/extract/task", request, Map.class);

                if (resp == null) {
                    throw new RuntimeException("Mineru submit returned null");
                }

                int code = ((Number) resp.getOrDefault("code", -1)).intValue();
                if (code != 0) {
                    throw new RuntimeException("Mineru submit failed: "
                            + resp.getOrDefault("msg", "unknown"));
                }

                Map<String, Object> data = (Map<String, Object>) resp.get("data");
                return (String) data.get("task_id");

            } catch (HttpClientErrorException e) {
                lastEx = e;
                if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    long backoff = (long) Math.pow(2, attempt + 1) * 1000;
                    log.warn("Mineru rate limited (429), retrying in {}ms", backoff);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                    continue;
                }
                throw e;
            }
        }
        throw new RuntimeException("Mineru submit failed after "
                + (MAX_RETRIES + 1) + " attempts", lastEx);
    }

    private byte[] pollResult(String taskId) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Polling interrupted", e);
            }

            Map<String, Object> pollResp;
            try {
                pollResp = mineruRestTemplate.getForObject(
                        "/api/v4/extract/task/{taskId}", Map.class, taskId);
            } catch (Exception e) {
                log.warn("Mineru poll failed, retrying: {}", e.getMessage());
                continue;
            }

            if (pollResp == null) {
                log.warn("Mineru poll returned null");
                continue;
            }

            Map<String, Object> data = (Map<String, Object>) pollResp.get("data");
            if (data == null) {
                log.warn("Mineru poll data is null");
                continue;
            }

            String state = (String) data.get("state");
            log.info("Mineru task {} state: {}", taskId, state);

            switch (state) {
                case "done" -> {
                    String zipUrl = (String) data.get("full_zip_url");
                    if (zipUrl == null) {
                        throw new RuntimeException("Mineru done but no full_zip_url");
                    }
                    log.info("Downloading result zip from: {}", zipUrl);
                    byte[] zipBytes = mineruRestTemplate.getForObject(zipUrl, byte[].class);
                    if (zipBytes == null || zipBytes.length == 0) {
                        throw new RuntimeException("Mineru zip download returned empty");
                    }
                    return zipBytes;
                }
                case "failed" -> {
                    String errMsg = (String) data.getOrDefault("err_msg", "未知错误");
                    throw new RuntimeException("Mineru parse failed: " + errMsg);
                }
                // pending / running / converting → continue polling
            }
        }

        throw new RuntimeException("Mineru poll timeout (" + POLL_TIMEOUT_MS + "ms) for task: " + taskId);
    }

    /**
     * 从Mineru返回的zip包中提取full.md.
     */
    private String extractMarkdownFromZip(byte[] zipBytes) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                // full.md 可能在zip根目录或子目录中
                if (name.endsWith("full.md") || name.equals("full.md")) {
                    byte[] content = zis.readAllBytes();
                    log.info("Extracted {} from zip ({} bytes)", name, content.length);
                    return new String(content, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract full.md from Mineru zip", e);
        }
        throw new RuntimeException("full.md not found in Mineru result zip");
    }
}
