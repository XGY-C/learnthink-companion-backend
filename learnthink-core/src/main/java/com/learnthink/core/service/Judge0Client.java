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
        String urlStr = baseUrl + "/submissions?wait=true&base64_encoded=false";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_code", sourceCode);
        body.put("language_id", languageId);
        body.put("stdin", stdin != null ? stdin : "");
        body.put("cpu_time_limit", cpuTimeLimit);
        body.put("memory_limit", memoryLimit);

        log.info("Judge0 submit: lang={}, codeLen={}", languageId, sourceCode.length());

        try {
            String jsonBody = mapper.writeValueAsString(body);
            log.debug("Judge0 request body: {}", jsonBody);

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

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            String responseBody;
            if (responseCode >= 200 && responseCode < 300) {
                try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8.name())) {
                    responseBody = scanner.useDelimiter("\\A").next();
                }
            } else {
                try (Scanner scanner = new Scanner(conn.getErrorStream(), StandardCharsets.UTF_8.name())) {
                    responseBody = scanner.useDelimiter("\\A").next();
                }
                log.error("Judge0 HTTP error: {} - {}", responseCode, responseBody);
                return CodeRunResult.builder()
                    .stderr("Judge0 HTTP error: " + responseCode + " - " + responseBody)
                    .status("HTTP Error")
                    .statusCode(12)
                    .build();
            }

            log.debug("Judge0 response: {}", responseBody);

            JsonNode node = mapper.readTree(responseBody);

            String statusDesc = node.has("status") && node.get("status").has("description")
                ? node.get("status").get("description").asText() : "Unknown";
            int statusCode = node.has("status") && node.get("status").has("id")
                ? node.get("status").get("id").asInt() : 0;

            return CodeRunResult.builder()
                .stdout(node.has("stdout") && !node.get("stdout").isNull()
                    ? node.get("stdout").asText() : null)
                .stderr(node.has("stderr") && !node.get("stderr").isNull()
                    ? node.get("stderr").asText() : null)
                .compileOutput(node.has("compile_output") && !node.get("compile_output").isNull()
                    ? node.get("compile_output").asText() : null)
                .status(statusDesc)
                .statusCode(statusCode)
                .time(node.has("time") && !node.get("time").isNull()
                    ? node.get("time").asDouble() : null)
                .memory(node.has("memory") && !node.get("memory").isNull()
                    ? node.get("memory").asDouble() : null)
                .build();

        } catch (Exception e) {
            log.error("Judge0 submission failed: {}", e.getMessage());
            return CodeRunResult.builder()
                .stderr("Judge0 service error: " + e.getMessage())
                .status("Internal Error")
                .statusCode(12)
                .build();
        }
    }
}
