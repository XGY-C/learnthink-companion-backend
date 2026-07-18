package com.learnthink.core.agent.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.CodeRunResult;
import com.learnthink.core.agent.runtime.AgentTool;
import com.learnthink.core.service.Judge0Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代码执行工具
 * <p>在隔离沙箱（Judge0）中编译运行代码，返回 stdout/stderr。
 * 支持 Python、C、C++、Java 四种语言。复用现有 {@link Judge0Client}。</p>
 *
 * <h3>Judge0 language_id 映射</h3>
 * <ul>
 *   <li>Python → 71 (Python 3)</li>
 *   <li>C → 50 (GCC 9.2.0)</li>
 *   <li>C++ → 54 (GCC 9.2.0 C++)</li>
 *   <li>Java → 62 (OpenJDK 13.0.1)</li>
 * </ul>
 */
public class CodeExecutionTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 语言名 → Judge0 language_id */
    private static final Map<String, Integer> LANGUAGE_IDS = Map.of(
            "python", 71,
            "c", 50,
            "cpp", 54,
            "java", 62
    );

    /** 语言别名 */
    private static final Map<String, String> LANGUAGE_ALIASES = Map.of(
            "py", "python",
            "python3", "python",
            "c++", "cpp",
            "cxx", "cpp"
    );

    private final Judge0Client judge0Client;

    public CodeExecutionTool(Judge0Client judge0Client) {
        this.judge0Client = judge0Client;
    }

    @Override
    public String name() {
        return "code_execution";
    }

    @Override
    public String description() {
        return "在隔离沙箱中编译运行代码并返回 stdout/stderr。" +
               "传入完整可直接执行的 source 代码和 language（python/c/cpp/java）。" +
               "用于计算验证、算法检查和数值验证--将结果 print 到 stdout。" +
               "注意：code 必须是可直接运行的代码，不要将代码包在 print 字符串里展示。" +
               "Python 沙箱仅支持标准库（math/random/sys 等），不支持 numpy/matplotlib 等第三方库。" +
               "不可用于替代推理解释。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "language": {
                  "type": "string",
                  "description": "编程语言：python、c、cpp 或 java",
                  "enum": ["python", "c", "cpp", "java"]
                },
                "code": {
                  "type": "string",
                  "description": "完整的源代码"
                },
                "stdin": {
                  "type": "string",
                  "description": "可选的标准输入内容"
                },
                "timeout": {
                  "type": "integer",
                  "description": "最大执行时间（秒），默认 10，最大 30",
                  "default": 10
                }
              },
              "required": ["language", "code"],
              "additionalProperties": false
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        long startTime = System.currentTimeMillis();
        log.info("[CODE_EXEC] ========== 工具调用开始 ==========");
        log.info("[CODE_EXEC] 原始入参(JSON): len={}", jsonArgs != null ? jsonArgs.length() : 0);
        if (jsonArgs != null && jsonArgs.length() > 500) {
            log.info("[CODE_EXEC] 原始入参预览: {}", jsonArgs.substring(0, 500) + "...");
        } else if (jsonArgs != null) {
            log.info("[CODE_EXEC] 原始入参: {}", jsonArgs);
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = MAPPER.readValue(jsonArgs, Map.class);
            log.info("[CODE_EXEC] 参数解析成功, keys={}", args.keySet());

            String rawLanguage = String.valueOf(args.getOrDefault("language", "")).trim().toLowerCase();
            String language = LANGUAGE_ALIASES.getOrDefault(rawLanguage, rawLanguage);
            log.info("[CODE_EXEC] 语言解析: raw='{}' -> normalized='{}'", rawLanguage, language);

            if (!LANGUAGE_IDS.containsKey(language)) {
                String errorMsg = "不支持的语言 '" + rawLanguage + "'，支持：python, c, cpp, java";
                log.warn("[CODE_EXEC] 语言校验失败: {}", errorMsg);
                return errorJson(errorMsg);
            }
            log.info("[CODE_EXEC] 语言校验通过, language_id={}", LANGUAGE_IDS.get(language));

            String code = String.valueOf(args.getOrDefault("code", "")).trim();
            if (code.isEmpty()) {
                String errorMsg = "code 不能为空";
                log.warn("[CODE_EXEC] 代码校验失败: {}", errorMsg);
                return errorJson(errorMsg);
            }
            log.info("[CODE_EXEC] 代码校验通过, 行数={}, 字符数={}",
                    code.split("\n").length, code.length());
            if (code.length() > 500) {
                log.info("[CODE_EXEC] 代码预览(前500字符):\n{}...", code.substring(0, 500));
            } else {
                log.info("[CODE_EXEC] 代码内容:\n{}", code);
            }

            String stdin = args.containsKey("stdin") ? String.valueOf(args.get("stdin")) : null;
            int timeout = args.containsKey("timeout")
                    ? Math.min(Math.max(((Number) args.get("timeout")).intValue(), 1), 30)
                    : 10;
            log.info("[CODE_EXEC] 执行参数: stdin={}, timeout={}s",
                    stdin != null ? ("len=" + stdin.length()) : "null", timeout);

            int languageId = LANGUAGE_IDS.get(language);
            int cpuTimeLimit = timeout;
            int memoryLimit = 256 * 1024;
            log.info("[CODE_EXEC] 沙箱参数: languageId={}, cpuTimeLimit={}s, memoryLimit={}MB",
                    languageId, cpuTimeLimit, memoryLimit / 1024);

            log.info("[CODE_EXEC] 开始调用 Judge0Client.submit()...");
            CodeRunResult result = judge0Client.submit(code, languageId, stdin, cpuTimeLimit, memoryLimit);
            long execTime = System.currentTimeMillis() - startTime;

            log.info("[CODE_EXEC] Judge0 返回: statusCode={}, status='{}', time={}s, memory={}KB",
                    result.getStatusCode(), result.getStatus(),
                    result.getTime(), result.getMemory() != null ? result.getMemory() / 1024 : null);

            if (result.getStdout() != null) {
                log.info("[CODE_EXEC] stdout(len={}): {}",
                        result.getStdout().length(),
                        result.getStdout().length() > 500 ? result.getStdout().substring(0, 500) + "..." : result.getStdout());
            } else {
                log.info("[CODE_EXEC] stdout: null");
            }

            if (result.getStderr() != null) {
                log.info("[CODE_EXEC] stderr(len={}): {}",
                        result.getStderr().length(),
                        result.getStderr().length() > 500 ? result.getStderr().substring(0, 500) + "..." : result.getStderr());
            } else {
                log.info("[CODE_EXEC] stderr: null");
            }

            if (result.getCompileOutput() != null) {
                log.info("[CODE_EXEC] compileOutput(len={}): {}",
                        result.getCompileOutput().length(),
                        result.getCompileOutput().length() > 500 ? result.getCompileOutput().substring(0, 500) + "..." : result.getCompileOutput());
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("language", language);
            response.put("status", result.getStatus());
            response.put("statusCode", result.getStatusCode());
            response.put("stdout", result.getStdout() != null ? result.getStdout() : "");
            response.put("stderr", result.getStderr() != null ? result.getStderr() : "");
            response.put("compileOutput", result.getCompileOutput() != null ? result.getCompileOutput() : "");
            if (result.getTime() != null) response.put("time", result.getTime());
            if (result.getMemory() != null) response.put("memory", result.getMemory());

            boolean success = result.getStatusCode() == 3;
            response.put("success", success);

            String responseJson = MAPPER.writeValueAsString(response);
            log.info("[CODE_EXEC] ========== 工具调用结束 ==========");
            log.info("[CODE_EXEC] 总耗时={}ms, success={}", execTime, success);

            return responseJson;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[CODE_EXEC] ========== 工具调用异常 ==========");
            log.error("[CODE_EXEC] 异常类型: {}, 消息: {}", e.getClass().getSimpleName(), e.getMessage());
            log.error("[CODE_EXEC] 异常堆栈:", e);
            log.error("[CODE_EXEC] 总耗时={}ms", elapsed);
            return errorJson("执行失败: " + e.getMessage());
        }
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "success", false,
                    "error", message,
                    "stdout", "",
                    "stderr", message
            ));
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
