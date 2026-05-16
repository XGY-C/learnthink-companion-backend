package com.learnthink.core.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.exception.ErrorCode;
import com.learnthink.common.util.TtsUtil;
import com.learnthink.core.domain.dto.*;
import com.learnthink.core.service.ExplanationVideoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 讲解视频服务实现类
 * @author 谢光益
 * @since 2026/3/2
 */
@Slf4j
@Service
public class ExplanationVideoServiceImpl implements ExplanationVideoService {

    private final ProjectBriefNormalizer projectBriefNormalizer;
    private final ChatClient aiVideoScriptGenerator;
    private final ChatClient aiSceneJsonGenerator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final TtsUtil ttsUtils;

    @Value("${manim.video.api.base-url}")
    private String manimApiBaseUrl;


    public ExplanationVideoServiceImpl(ProjectBriefNormalizer projectBriefNormalizer,
                                       @Qualifier("videoScriptChatClient") ChatClient aiVideoScriptGenerator,
                                       @Qualifier("videoSceneChatClient") ChatClient aiSceneJsonGenerator,
                                       RestTemplate restTemplate,
                                       TtsUtil ttsUtils) {
        this.projectBriefNormalizer = projectBriefNormalizer;
        this.aiVideoScriptGenerator = aiVideoScriptGenerator;
        this.aiSceneJsonGenerator = aiSceneJsonGenerator;
        this.restTemplate = restTemplate;
        this.ttsUtils = ttsUtils;
    }

    @Override
    public ExplanationVideoDTO generateVideo(ProjectInput projectInput, Long userId) throws JsonProcessingException {
        if(userId == null){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "用户ID不能为空");
        }
        if(projectInput == null){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "视频输入参数不能为空");
        }
        // 1.需求标准化
        ProjectBrief projectBrief = projectBriefNormalizer.normalize(projectInput);
        log.info("需求为：{}", projectBrief);
        // 需求转为json字符串
        String projectBriefJson = objectMapper.writeValueAsString(projectBrief);
        log.info("需求转为json字符串为：{}", projectBriefJson);
        // 2.根据需求生成视频脚本（System Prompt 已在 Bean 中预设）
        String rawScript = aiVideoScriptGenerator.prompt()
            .user(projectBriefJson)
            .call().content();
        log.info("AI给出的视频脚本为：{}", rawScript);
        String script = cleanMarkdownJson(rawScript);
        log.info("视频脚本为：{}", script);
        // 3.根据视频脚本生成分镜脚本（wenSystem Prompt 已在 Bean 中预设）
        String rawSceneJson = aiSceneJsonGenerator.prompt()
            .user(script)
            .call().content();
        log.info("AI给出的分镜脚本为：{}", rawSceneJson);
        String sceneJson = cleanMarkdownJson(rawSceneJson);
        log.info("分镜脚本为：{}", sceneJson);
        // 4.并行合成所有 scene 的音频，并返回标准化后的 ttsResults（只保留 url 和时刻）
        ArrayNode ttsResults = synthesizeAllAudio(script, sceneJson, 4);
        log.info("语音合成结果为：{}", ttsResults);
        // 5.合并分镜脚本和语音合成结果
        sceneJson = mergeFinalJson(script, sceneJson, ttsResults);
        log.info("合并后的分镜脚本为：{}", sceneJson);
        // 6.根据分镜脚本和视频信息生成视频
        String videoUrl = generateVideoBySceneJson(sceneJson, projectBrief);
        log.info("视频url为：{}", videoUrl);
        // 6.返回视频vo
        ExplanationVideoDTO ev = new ExplanationVideoDTO();
        ev.setTitle(projectBrief.getTopic());
        ev.setDuration(projectBrief.getTargetDurationSec());
        ev.setVideoUrl(videoUrl);
        return ev;
    }

    /**
     * 根据分镜脚本生成视频
     * @param sceneJson 分镜脚本json字符串
     * @param projectBrief 项目简介
     * @return 视频url字符串
     */
    private String generateVideoBySceneJson(String sceneJson, ProjectBrief projectBrief) {
        try {
            // 1. 解析分镜脚本JSON
            JsonNode sceneNode = objectMapper.readTree(sceneJson);
            
            // 2. 构建请求体
            ManimVideoRenderRequest request = new ManimVideoRenderRequest();

            // 视频项目信息
            request.setProjectBrief(projectBrief);
            log.info("projectBrief内容: {}", objectMapper.writeValueAsString(projectBrief));
            
            // 3. 将 sceneBlocks 转换为符合 API 要求的 timedScenes 数组
            ArrayNode timedScenes = objectMapper.createArrayNode();
            
            // 获取 sceneBlocks 和 ttsResults
            JsonNode sceneBlocksNode = sceneNode.get("sceneBlocks");
            JsonNode ttsResultsNode = sceneNode.get("ttsResults");
            
            if (sceneBlocksNode == null || !sceneBlocksNode.isArray()) {
                throw new BusinessException(ErrorCode.VIDEO_PARAM_ERROR, "sceneJson 中缺少 sceneBlocks 数组");
            }
            
            log.info("sceneBlocks数量: {}", sceneBlocksNode.size());
            log.info("ttsResults数量: {}", ttsResultsNode != null ? ttsResultsNode.size() : 0);
            
            // 构建 ttsResults 映射: sceneId -> ttsResult
            Map<String, JsonNode> ttsResultMap = new HashMap<>();
            if (ttsResultsNode != null && ttsResultsNode.isArray()) {
                for (JsonNode ttsResult : ttsResultsNode) {
                    String sceneId = getTextValue(ttsResult, "sceneId");
                    if (sceneId != null) {
                        ttsResultMap.put(sceneId, ttsResult);
                    }
                }
            }
            
            // 遍历 sceneBlocks，构建每个 timedScene
            for (JsonNode sceneBlock : sceneBlocksNode) {
                ObjectNode timedScene = objectMapper.createObjectNode();
                
                // 获取 sceneId
                String sceneId = getTextValue(sceneBlock, "id");
                if (sceneId == null || sceneId.isBlank()) {
                    throw new BusinessException(ErrorCode.VIDEO_PARAM_ERROR, "sceneBlock 中存在空的 sceneId");
                }
                
                // 必填字段: sceneId
                timedScene.put("sceneId", sceneId);
                
                // 获取对应的 ttsResult
                JsonNode ttsResult = ttsResultMap.get(sceneId);
                
                // 计算 durationSec（从 ttsResult 的句子时间计算）
                double durationSec = 0.0;
                if (ttsResult != null) {
                    JsonNode sentences = ttsResult.get("sentences");
                    if (sentences != null && sentences.isArray() && sentences.size() > 0) {
                        // 获取最后一个句子的 end_time
                        JsonNode lastSentence = sentences.get(sentences.size() - 1);
                        String endTimeStr = getTextValue(lastSentence, "end_time");
                        if (endTimeStr != null && !endTimeStr.isEmpty()) {
                            try {
                                durationSec = Double.parseDouble(endTimeStr) / 1000.0; // 毫秒转秒
                            } catch (NumberFormatException e) {
                                log.warn("无法解析 end_time: {}", endTimeStr);
                            }
                        }
                    }
                }
                
                // 必填字段: durationSec
                timedScene.put("durationSec", durationSec);
                
                // 可选字段: audioUrl（从 ttsResult 获取）
                if (ttsResult != null) {
                    String audioAddress = getTextValue(ttsResult, "audioAddress");
                    if (audioAddress != null && !audioAddress.isEmpty()) {
                        timedScene.put("audioUrl", audioAddress);
                    }
                    
                    // 构建 sentences 数组
                    JsonNode sentences = ttsResult.get("sentences");
                    if (sentences != null && sentences.isArray()) {
                        ArrayNode sentencesArray = objectMapper.createArrayNode();
                        int index = 0;
                        for (JsonNode sentence : sentences) {
                            ObjectNode sentenceObj = objectMapper.createObjectNode();
                            sentenceObj.put("index", ++index);
                            sentenceObj.put("text", getTextValue(sentence, "text"));
                            
                            String beginTimeStr = getTextValue(sentence, "begin_time");
                            String endTimeStr = getTextValue(sentence, "end_time");
                            
                            if (beginTimeStr != null && !beginTimeStr.isEmpty()) {
                                try {
                                    sentenceObj.put("startSec", Double.parseDouble(beginTimeStr) / 1000.0);
                                } catch (NumberFormatException e) {
                                    log.warn("无法解析 begin_time: {}", beginTimeStr);
                                }
                            }
                            
                            if (endTimeStr != null && !endTimeStr.isEmpty()) {
                                try {
                                    sentenceObj.put("endSec", Double.parseDouble(endTimeStr) / 1000.0);
                                } catch (NumberFormatException e) {
                                    log.warn("无法解析 end_time: {}", endTimeStr);
                                }
                            }
                            
                            sentencesArray.add(sentenceObj);
                        }
                        timedScene.set("sentences", sentencesArray);
                        
                        // 构建 subtitleItems（与 sentences 相同结构）
                        timedScene.set("subtitleItems", sentencesArray.deepCopy());
                    }
                }
                
                // 构建 sceneSpec（从 sceneBlock 转换）
                ObjectNode sceneSpec = objectMapper.createObjectNode();
                
                // layoutTemplate
                String layoutTemplate = getTextValue(sceneBlock, "layoutTemplate");
                if (layoutTemplate != null) {
                    sceneSpec.put("layoutTemplate", layoutTemplate);
                }
                
                // objects 数组
                JsonNode objects = sceneBlock.get("objects");
                if (objects != null && objects.isArray()) {
                    sceneSpec.set("objects", objects);
                }
                
                timedScene.set("sceneSpec", sceneSpec);
                
                // 构建 animationCues（从 animationPlan 转换）
                JsonNode animationPlan = sceneBlock.get("animationPlan");
                if (animationPlan != null && animationPlan.isArray()) {
                    ArrayNode animationCues = objectMapper.createArrayNode();
                    for (JsonNode cue : animationPlan) {
                        ObjectNode animationCue = objectMapper.createObjectNode();
                        
                        String cueId = getTextValue(cue, "id");
                        if (cueId != null) {
                            animationCue.put("id", cueId);
                        }
                        
                        JsonNode targetRefs = cue.get("targetRefs");
                        if (targetRefs != null) {
                            animationCue.set("targetRefs", targetRefs);
                        }
                        
                        String action = getTextValue(cue, "action");
                        if (action != null) {
                            animationCue.put("action", action);
                        }
                        
                        String intent = getTextValue(cue, "intent");
                        if (intent != null) {
                            animationCue.put("intent", intent);
                        }
                        
                        // timeSec 和 runTimeSec 需要根据 trigger 计算，这里暂时设为 0
                        animationCue.put("timeSec", 0.0);
                        animationCue.put("runTimeSec", 1.0);
                        
                        animationCues.add(animationCue);
                    }
                    timedScene.set("animationCues", animationCues);
                }
                
                timedScenes.add(timedScene);
            }
            
            request.setTimedScenes(objectMapper.convertValue(timedScenes, List.class));
            
            log.info("timedScenes数量: {}", timedScenes.size());
            log.info("最终请求体: {}", objectMapper.writeValueAsString(request));
            
            // 3. 发送HTTP请求
            String url = manimApiBaseUrl + "/v1/video/render";
            log.info("调用Manim视频API: {}", url);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            String requestBody = objectMapper.writeValueAsString(request);
            log.info("请求体字节长度: {} bytes (UTF-8)", requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            log.debug("请求体内容: {}", requestBody);
            
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<ManimVideoRenderResponse> response = restTemplate.postForEntity(
                url, entity, ManimVideoRenderResponse.class
            );
            
            // 4. 处理响应
            log.info("Manim API响应状态码: {}", response.getStatusCode());
            log.info("Manim API响应头: {}", response.getHeaders());
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                ManimVideoRenderResponse renderResponse = response.getBody();
                
                if (Boolean.TRUE.equals(renderResponse.getSuccess())) {
                    log.info("视频生成成功, taskId: {}, videoUrl: {}", 
                        renderResponse.getTaskId(), renderResponse.getVideoUrl());
                    return renderResponse.getVideoUrl();
                } else {
                    log.error("视频生成失败: {}, attempts: {}", 
                        renderResponse.getMessage(), renderResponse.getAttempts());
                    throw new BusinessException(ErrorCode.VIDEO_GENERATION_FAILED, "视频生成失败: " + renderResponse.getMessage());
                }
            } else {
                throw new BusinessException(ErrorCode.VIDEO_SERVICE_ERROR, "视频生成服务响应异常");
            }
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用Manim视频API异常", e);
            throw new BusinessException(ErrorCode.VIDEO_GENERATION_FAILED, "视频生成失败: " + e.getMessage());
        }
    }


    /**
     * 并行合成所有 scene 的音频，并返回标准化后的 ttsResults（只保留 url 和时刻）
     *
     * 返回的每一项结构：
     * {
     *   "ttsId": "TTS001",
     *   "sceneId": "SC001",
     *   "audioAddress": "...",
     *   "sentences": [
     *     {"text":"...", "begin_time":"0", "end_time":"4247"}
     *   ]
     * }
     */
    public ArrayNode synthesizeAllAudio(String scriptJson, String sceneJson, int threadCount) {
        if (threadCount <= 0) {
            threadCount = 4;
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            JsonNode sceneRoot = objectMapper.readTree(sceneJson);
            JsonNode sceneBlocksNode = sceneRoot.get("sceneBlocks");
            if (sceneBlocksNode == null || !sceneBlocksNode.isArray() || sceneBlocksNode.isEmpty()) {
                throw new BusinessException(ErrorCode.VIDEO_PARAM_ERROR, "sceneJson 中缺少 sceneBlocks 数组");
            }

            List<Future<ObjectNode>> futures = new ArrayList<>();

            for (JsonNode sceneBlock : sceneBlocksNode) {
                String sceneId = getTextValue(sceneBlock, "id");
                if (sceneId == null || sceneId.isBlank()) {
                    throw new BusinessException(ErrorCode.VIDEO_PARAM_ERROR, "sceneBlocks 中存在空的 sceneId");
                }

                futures.add(executor.submit(() -> {
                    try {
                        log.info("========== 开始处理场景音频 ==========");
                        log.info("sceneId: {}", sceneId);
                        
                        String script = buildTtsText(scriptJson, sceneJson, sceneId);
                        log.info("构建的TTS文本长度: {}", script.length());
                        log.info("TTS文本内容: {}", script.length() > 200 ? script.substring(0, 200) + "..." : script);

                        log.info("调用TTS合成接口...");
                        TtsUtil.TaskStatus ttsResult = ttsUtils.synthesizeLongTextAndUpload(script);
                        
                        if (ttsResult == null) {
                            log.error("❌ TTS返回结果为null");
                            throw new BusinessException(ErrorCode.TTS_SUBMIT_FAILED, "sceneId=" + sceneId + " 的 TTS 返回为空");
                        }

                        log.info("TTS任务状态: {}", ttsResult.getStatus());
                        log.info("音频地址: {}", ttsResult.getAudioAddress());
                        
                        String audioAddress = ttsResult.getAudioAddress();
                        if (audioAddress == null || audioAddress.isBlank()) {
                            log.error("❌ audioAddress为空");
                            throw new BusinessException(ErrorCode.TTS_DOWNLOAD_FAILED, "sceneId=" + sceneId + " 的 audioAddress 为空，任务状态: " + ttsResult.getStatus());
                        }

                        ObjectNode ttsNode = objectMapper.createObjectNode();
                        ttsNode.put("ttsId", generateTtsId(sceneId));
                        ttsNode.put("sceneId", sceneId);
                        ttsNode.put("audioAddress", audioAddress);

                        ArrayNode sentencesNode = objectMapper.createArrayNode();
                        List<?> sentences = ttsResult.getSentences();
                        if (sentences != null) {
                            log.info("句子数量: {}", sentences.size());
                            for (Object sentence : sentences) {
                                ObjectNode sentenceNode = objectMapper.createObjectNode();
                                sentenceNode.put("text", safeString(readProperty(sentence, "text", "getText")));

                                Object beginVal = readProperty(sentence, "begin_time", "beginTime", "getBegin_time", "getBeginTime");
                                Object endVal = readProperty(sentence, "end_time", "endTime", "getEnd_time", "getEndTime");

                                sentenceNode.put("begin_time", safeString(beginVal));
                                sentenceNode.put("end_time", safeString(endVal));

                                sentencesNode.add(sentenceNode);
                            }
                        }

                        ttsNode.set("sentences", sentencesNode);
                        log.info("✅ 场景音频处理成功");
                        log.info("========================================");
                        return ttsNode;
                    } catch (BusinessException e) {
                        log.error("========== ❌ 场景音频处理失败 ==========");
                        log.error("sceneId: {}", sceneId);
                        log.error("异常类型: {}", e.getClass().getName());
                        log.error("异常消息: {}", e.getMessage());
                        log.error("堆栈跟踪:", e);
                        log.error("========================================");
                        throw e;
                    } catch (Exception e) {
                        log.error("========== ❌ 场景音频处理失败 ==========");
                        log.error("sceneId: {}", sceneId);
                        log.error("异常类型: {}", e.getClass().getName());
                        log.error("异常消息: {}", e.getMessage());
                        log.error("堆栈跟踪:", e);
                        log.error("========================================");
                        throw new BusinessException(ErrorCode.TTS_SUBMIT_FAILED, "sceneId=" + sceneId + " 音频合成失败: " + e.getMessage());
                    }
                }));
            }

            log.info("等待所有并行任务完成，总数: {}", futures.size());
            ArrayNode resultArray = objectMapper.createArrayNode();
            int index = 0;
            for (Future<ObjectNode> future : futures) {
                try {
                    log.debug("获取第 {}/{} 个任务结果...", index + 1, futures.size());
                    resultArray.add(future.get());
                    log.debug("✅ 第 {}/{} 个任务完成", index + 1, futures.size());
                } catch (Exception e) {
                    log.error("========== ❌ 获取并行任务结果失败 ==========");
                    log.error("任务索引: {}/{}", index + 1, futures.size());
                    log.error("异常类型: {}", e.getClass().getName());
                    log.error("异常消息: {}", e.getMessage());
                    
                    Throwable cause = e.getCause();
                    if (cause != null) {
                        log.error("根本原因类型: {}", cause.getClass().getName());
                        log.error("根本原因消息: {}", cause.getMessage());
                        log.error("根本原因堆栈:", cause);
                        throw new BusinessException(ErrorCode.TTS_QUERY_FAILED, "获取音频合成结果失败: " + cause.getMessage());
                    } else {
                        log.error("堆栈跟踪:", e);
                        throw new BusinessException(ErrorCode.TTS_QUERY_FAILED, "获取音频合成结果失败: " + e.getMessage());
                    }
                }
                index++;
            }

            log.info("========== ✅ 所有音频合成完成 ==========");
            log.info("总场景数: {}", resultArray.size());
            return resultArray;

        } catch (BusinessException e) {
            log.error("========== ❌ 并行合成音频失败 ==========");
            log.error("异常类型: {}", e.getClass().getName());
            log.error("异常消息: {}", e.getMessage());
            log.error("堆栈跟踪:", e);
            log.error("==========================================");
            throw e;
        } catch (Exception e) {
            log.error("========== ❌ 并行合成音频失败 ==========");
            log.error("异常类型: {}", e.getClass().getName());
            log.error("异常消息: {}", e.getMessage());
            log.error("堆栈跟踪:", e);
            log.error("==========================================");
            throw new BusinessException(ErrorCode.TTS_SUBMIT_FAILED, "并行合成音频失败: " + e.getMessage());
        } finally {
            executor.shutdown();
            log.info("线程池已关闭");
        }
    }

    /**
     * 把 scriptJson + sceneJson + ttsResults 合并成最终 JSON 字符串
     *
     * 最终结构：
     * {
     *   ...scriptJson 原有字段,
     *   "sceneBlocks": [...],
     *   "ttsResults": [...]
     * }
     */
    public String mergeFinalJson(String scriptJson, String sceneJson, ArrayNode ttsResults) {
        try {
            if (scriptJson == null || scriptJson.isBlank()) {
                throw new IllegalArgumentException("scriptJson 不能为空");
            }
            if (sceneJson == null || sceneJson.isBlank()) {
                throw new IllegalArgumentException("sceneJson 不能为空");
            }
            if (ttsResults == null) {
                throw new IllegalArgumentException("ttsResults 不能为空");
            }

            JsonNode scriptRoot = objectMapper.readTree(scriptJson);
            JsonNode sceneRoot = objectMapper.readTree(sceneJson);

            if (!(scriptRoot instanceof ObjectNode)) {
                throw new IllegalArgumentException("scriptJson 顶层必须是对象");
            }
            if (!(sceneRoot instanceof ObjectNode)) {
                throw new IllegalArgumentException("sceneJson 顶层必须是对象");
            }

            ObjectNode finalRoot = ((ObjectNode) scriptRoot).deepCopy();

            JsonNode sceneBlocksNode = sceneRoot.get("sceneBlocks");
            if (sceneBlocksNode == null || !sceneBlocksNode.isArray()) {
                throw new IllegalArgumentException("sceneJson 中缺少 sceneBlocks 数组");
            }

            finalRoot.set("sceneBlocks", sceneBlocksNode);
            finalRoot.set("ttsResults", ttsResults);

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(finalRoot);

        } catch (Exception e) {
            throw new RuntimeException("合并最终 JSON 失败: " + e.getMessage(), e);
        }
    }

    private static String generateTtsId(String sceneId) {
        if (sceneId == null || !sceneId.startsWith("SC")) {
            throw new IllegalArgumentException("sceneId 格式非法: " + sceneId);
        }
        return "TTS" + sceneId.substring(2);
    }

    private static String getTextValue(JsonNode node, String fieldName) {
        JsonNode valueNode = node.get(fieldName);
        return valueNode == null ? null : valueNode.asText();
    }

    private static String safeString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 清理 AI 返回的 Markdown 格式 JSON，提取纯 JSON 内容
     * 处理格式：```json {...} ``` 或 ``` {...}
     */
    private String cleanMarkdownJson(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) {
            return rawJson;
        }
        
        String cleaned = rawJson.trim();
        
        // 检查是否以 ``` 开头
        if (cleaned.startsWith("```")) {
            // 找到第一个换行符的位置（```json 或 ``` 后面）
            int firstNewLine = cleaned.indexOf('\n');
            if (firstNewLine != -1) {
                cleaned = cleaned.substring(firstNewLine + 1);
            } else {
                // 没有换行符，直接去掉开头的 ```
                cleaned = cleaned.substring(3);
            }
            
            // 去掉末尾的 ```
            int lastBackticks = cleaned.lastIndexOf("```");
            if (lastBackticks != -1) {
                cleaned = cleaned.substring(0, lastBackticks);
            }
            
            cleaned = cleaned.trim();
            log.debug("已清理 Markdown 标记，原始长度: {}, 清理后长度: {}", 
                    rawJson.length(), cleaned.length());
        }
        
        return cleaned;
    }

    /**
     * 兼容不同 Sentence 类实现：
     * 优先找字段，再找 getter
     */
    private static Object readProperty(Object obj, String... names) {
        if (obj == null || names == null) {
            return null;
        }

        Class<?> clazz = obj.getClass();

        for (String name : names) {
            // 1. 先尝试字段
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(obj);
            } catch (Exception ignored) {
            }

            // 2. 再尝试方法
            try {
                Method method = clazz.getMethod(name);
                return method.invoke(obj);
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    /**
     * 根据 scriptJson + sceneJson + sceneId，拼出当前 scene 对应的 TTS 文本
     */
    public String buildTtsText(String scriptJson, String sceneJson, String sceneId) {
        try {
            if (scriptJson == null || scriptJson.isBlank()) {
                throw new IllegalArgumentException("scriptJson 不能为空");
            }
            if (sceneJson == null || sceneJson.isBlank()) {
                throw new IllegalArgumentException("sceneJson 不能为空");
            }
            if (sceneId == null || sceneId.isBlank()) {
                throw new IllegalArgumentException("sceneId 不能为空");
            }

            JsonNode scriptRoot = objectMapper.readTree(scriptJson);
            JsonNode sceneRoot = objectMapper.readTree(sceneJson);

            JsonNode scriptBlocksNode = scriptRoot.get("scriptBlocks");
            JsonNode sceneBlocksNode = sceneRoot.get("sceneBlocks");

            if (scriptBlocksNode == null || !scriptBlocksNode.isArray()) {
                throw new IllegalArgumentException("scriptJson 中缺少 scriptBlocks 数组");
            }
            if (sceneBlocksNode == null || !sceneBlocksNode.isArray()) {
                throw new IllegalArgumentException("sceneJson 中缺少 sceneBlocks 数组");
            }

            // 1. 建立 scriptBlock id -> text 的映射
            Map<String, String> scriptTextMap = new LinkedHashMap<>();
            for (JsonNode scriptBlock : scriptBlocksNode) {
                String id = getTextValue(scriptBlock, "id");
                String text = getTextValue(scriptBlock, "text");
                if (id != null && !id.isBlank() && text != null && !text.isBlank()) {
                    scriptTextMap.put(id, text.trim());
                }
            }

            if (scriptTextMap.isEmpty()) {
                throw new IllegalArgumentException("scriptBlocks 中没有可用的 id/text");
            }

            // 2. 找到目标 scene
            JsonNode targetScene = null;
            for (JsonNode sceneBlock : sceneBlocksNode) {
                String id = getTextValue(sceneBlock, "id");
                if (sceneId.equals(id)) {
                    targetScene = sceneBlock;
                    break;
                }
            }

            if (targetScene == null) {
                throw new IllegalArgumentException("找不到指定的 sceneId: " + sceneId);
            }

            JsonNode scriptRefsNode = targetScene.get("scriptRefs");
            if (scriptRefsNode == null || !scriptRefsNode.isArray() || scriptRefsNode.isEmpty()) {
                throw new IllegalArgumentException("sceneId=" + sceneId + " 的 scriptRefs 为空");
            }

            // 3. 按 scriptRefs 顺序拼接 text
            StringBuilder sb = new StringBuilder();
            Iterator<JsonNode> it = scriptRefsNode.elements();
            while (it.hasNext()) {
                JsonNode refNode = it.next();
                String scriptRef = refNode.asText();
                String text = scriptTextMap.get(scriptRef);

                if (text == null || text.isBlank()) {
                    throw new IllegalArgumentException("scriptRefs 引用了不存在或空文本的脚本块: " + scriptRef);
                }

                sb.append(text.trim());
            }

            return sb.toString();

        } catch (Exception e) {
            throw new RuntimeException("构建 TTS text 失败: " + e.getMessage(), e);
        }
    }
}
