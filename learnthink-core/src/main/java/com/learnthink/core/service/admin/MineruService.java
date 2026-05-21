package com.learnthink.core.service.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * <p>限制: ≤200MB, ≤200页</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MineruService {

    private final RestTemplate mineruRestTemplate;

    private static final int MAX_RETRIES = 3;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${mineru.api.token}")
    private String mineruToken;

    /**
     * 提交PDF URL给Mineru，返回taskId.
     */
    public String submitTask(String fileUrl) {
        // 预序列化JSON，避免RestTemplate消息转换器处理Map时的行为不确定性
        String requestJson;
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("url", fileUrl);
            request.put("model_version", "vlm");
            requestJson = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize Mineru request", e);
        }

        Exception lastEx = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("Submitting Mineru extract task (attempt {}/{}): {}",
                        attempt + 1, MAX_RETRIES + 1, fileUrl);
                log.info("Mineru request body: {}", requestJson);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(mineruToken);
                HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
                Map<String, Object> resp = mineruRestTemplate.postForObject(
                        "/api/v4/extract/task", entity, Map.class);

                if (resp == null) {
                    throw new RuntimeException("Mineru submit returned null");
                }

                int code = ((Number) resp.getOrDefault("code", -1)).intValue();
                if (code != 0) {
                    throw new RuntimeException("Mineru submit failed: "
                            + resp.getOrDefault("msg", "unknown")
                            + " | full response: " + resp);
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

    /**
     * 单次轮询Mineru任务状态.
     */
    public MineruTaskStatus getTaskStatus(String mineruTaskId) {
        Map<String, Object> resp = mineruRestTemplate.getForObject(
                "/api/v4/extract/task/{taskId}", Map.class, mineruTaskId);

        if (resp == null) {
            throw new RuntimeException("Mineru poll returned null");
        }

        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        if (data == null) {
            throw new RuntimeException("Mineru poll data is null");
        }

        MineruTaskStatus status = new MineruTaskStatus();
        status.setState((String) data.get("state"));
        status.setProgress(((Number) data.getOrDefault("progress", 0)).intValue());
        status.setErrMsg((String) data.get("err_msg"));
        status.setFullZipUrl((String) data.get("full_zip_url"));
        return status;
    }

    /**
     * 下载Mineru返回的zip包.
     */
    public byte[] downloadZip(String fullZipUrl) {
        log.info("Downloading result zip from: {}", fullZipUrl);
        byte[] zipBytes = mineruRestTemplate.getForObject(fullZipUrl, byte[].class);
        if (zipBytes == null || zipBytes.length == 0) {
            throw new RuntimeException("Mineru zip download returned empty");
        }
        log.info("Downloaded zip ({} bytes)", zipBytes.length);
        return zipBytes;
    }

    /**
     * 从zip包中提取full.md内容.
     */
    public String extractMarkdown(byte[] zipBytes) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
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

    /**
     * 同步解析: 提交 → 轮询等待完成 → 下载 → 提取MD.
     */
    public String parseDocument(String fileUrl) {
        String taskId = submitTask(fileUrl);
        log.info("Mineru task submitted, taskId: {}", taskId);

        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10);
        MineruTaskStatus status;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Polling interrupted", e);
            }

            try {
                status = getTaskStatus(taskId);
            } catch (Exception e) {
                log.warn("Mineru poll failed, retrying: {}", e.getMessage());
                continue;
            }

            String state = status.getState();
            log.info("Mineru task {} state: {} ({}%)", taskId, state, status.getProgress());

            switch (state) {
                case "done" -> {
                    byte[] zipBytes = downloadZip(status.getFullZipUrl());
                    return extractMarkdown(zipBytes);
                }
                case "failed" -> throw new RuntimeException("Mineru parse failed: "
                        + (status.getErrMsg() != null ? status.getErrMsg() : "未知错误"));
            }
        }

        throw new RuntimeException("Mineru poll timeout for task: " + taskId);
    }
}
