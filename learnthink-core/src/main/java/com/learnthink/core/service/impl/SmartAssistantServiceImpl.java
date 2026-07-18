package com.learnthink.core.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.chat.SceneStepVO;
import com.learnthink.common.dto.chat.SceneVO;
import com.learnthink.common.dto.chat.SmartAssistantRequest;
import com.learnthink.common.dto.chat.SmartExplanationItemVO;
import com.learnthink.common.dto.chat.SseEvent;
import com.learnthink.common.util.TtsUtil;
import com.learnthink.core.service.SmartAssistantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能助手服务实现类 —— 视频讲解 Scene 协议生成（含 TTS 时间戳）
 * <p>LLM 生成场景 JSON → 阿里云长文本 TTS 合成 → 句子级时间戳回填 duration/steps/audioUrl，
 * 再以 SSE item 事件推送给前端 VideoLecturePlayer。</p>
 *
 * @author 谢光益
 * @since 2026/5/26
 */
@Slf4j
@Service
public class SmartAssistantServiceImpl implements SmartAssistantService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatClient sceneChatClient;
    private final TtsUtil ttsUtil;

    public SmartAssistantServiceImpl(
            @Qualifier("smartAssistantSceneChatClient") ChatClient sceneChatClient,
            TtsUtil ttsUtil) {
        this.sceneChatClient = sceneChatClient;
        this.ttsUtil = ttsUtil;
    }

    @Override
    public Flux<SseEvent> answer(SmartAssistantRequest request, String userId) {
        if (userId == null || userId.isBlank()) {
            return Flux.error(new IllegalArgumentException("用户ID不能为空"));
        }
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            return Flux.error(new IllegalArgumentException("问题不能为空"));
        }

        String question = request.getQuestion();
        log.info("开始视频讲解（含TTS），用户 {} 问题：{}", userId, question);

        return Flux.concat(
                Flux.just(SseEvent.named("connected", "{\"message\":\"连接成功\"}")),
                sceneChatClient.prompt()
                        .user(question)
                        .stream()
                        .chatResponse()
                        .doOnNext(resp -> {
                            // 诊断：检测 reasoning_content（思考模式未正确禁用的标志）
                            if (resp.getResult() != null && resp.getResult().getOutput() != null
                                    && resp.getResult().getOutput().getMetadata() != null) {
                                String reasoning = (String) resp.getResult().getOutput().getMetadata().get("reasoning_content");
                                if (reasoning != null && !reasoning.isEmpty()) {
                                    log.warn("检测到 reasoning_content（长度={}），思考模式可能未正确禁用。" +
                                            "请检查 extraBody 中 thinking 参数是否在流式请求中生效。", reasoning.length());
                                }
                            }
                        })
                        .map(resp -> {
                            if (resp.getResult() != null && resp.getResult().getOutput() != null) {
                                String text = resp.getResult().getOutput().getText();
                                return text != null ? text : "";
                            }
                            return "";
                        })
                        .filter(text -> !text.isEmpty())
                        .transform(this::extractItemsFromStream)
                        .flatMapSequential(item -> processItemWithTts(item), 4)
                        // 修复：LLM 流级别错误发射 error 事件，不再吞掉
                        // TTS 错误已由 processItemWithTts 内部的 onErrorResume 降级处理
                        .onErrorResume(error -> {
                            log.error("视频讲解流处理发生错误", error);
                            String msg = error.getMessage() != null ? error.getMessage() : "未知错误";
                            try {
                                Map<String, String> errorPayload = Map.of("message", "处理失败: " + msg);
                                return Flux.just(SseEvent.named("error",
                                        objectMapper.writeValueAsString(errorPayload)));
                            } catch (Exception e) {
                                return Flux.just(SseEvent.named("error",
                                        "{\"message\":\"处理失败\"}"));
                            }
                        }),
                Flux.just(SseEvent.named("done", "{\"message\":\"回答完成\"}"))
        );
    }

    /**
     * 对单个场景调用阿里云长文本 TTS，将句子时间戳回填到 SceneVO。
     * <p>在 boundedElastic 线程池上执行，避免阻塞事件循环。</p>
     */
    private Mono<SseEvent> processItemWithTts(SmartExplanationItemVO item) {
        SceneVO scene = item.getScene();
        if (scene == null || scene.getNarration() == null || scene.getNarration().isBlank()) {
            // 无 narration 的场景直接透传（兼容旧格式）
            return Mono.fromCallable(() -> buildItemEvent(item));
        }

        return Mono.fromCallable(() -> {
                    log.debug("开始 TTS 合成，sceneIndex={}, narration长度={}", item.getSceneIndex(), scene.getNarration().length());
                    return ttsUtil.synthesizeLongText(scene.getNarration());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ttsResult -> {
                    mergeTtsToScene(scene, ttsResult);
                    log.debug("TTS 合成完成，sceneIndex={}, duration={}ms, audioUrl={}",
                            item.getSceneIndex(), scene.getDuration(), scene.getAudioUrl());
                    return buildItemEvent(item);
                })
                .onErrorResume(err -> {
                    log.error("TTS 合成失败，sceneIndex={}, 使用估算时长", item.getSceneIndex(), err);
                    // TTS 失败时用文本长度估算 duration，不阻塞流程
                    scene.setDuration(Math.max(scene.getNarration().length() * 240, 3000));
                    return Mono.fromCallable(() -> buildItemEvent(item));
                });
    }

    /**
     * 将 TTS 返回的句子级时间戳合并到 SceneVO。
     * <ul>
     *   <li>duration = 最后一个句子的 endTime（ms）</li>
     *   <li>steps = 每个句子的 beginTime 生成的动画步骤</li>
     *   <li>audioUrl = TTS 音频地址</li>
     * </ul>
     */
    private void mergeTtsToScene(SceneVO scene, TtsUtil.TaskStatus ttsResult) {
        if (ttsResult == null) return;

        // 上传音频到 OSS 并回填 URL
        try {
            String ossUrl = ttsUtil.uploadAudio(
                    ttsResult.getAudioAddress(),
                    "scene_" + System.currentTimeMillis() + ".wav",
                    "audio/"
            );
            scene.setAudioUrl(ossUrl);
        } catch (Exception e) {
            log.warn("TTS 音频上传 OSS 失败，使用原始地址", e);
            scene.setAudioUrl(ttsResult.getAudioAddress());
        }

        List<TtsUtil.TtsQueryResponse.Sentence> sentences = ttsResult.getSentences();
        if (sentences == null || sentences.isEmpty()) {
            // 无句子数据时用 narration 长度估算
            scene.setDuration(Math.max(scene.getNarration().length() * 240, 3000));
            return;
        }

        // 提取时间戳
        int firstBegin = parseTimeToMs(sentences.get(0).getBeginTime());
        int lastEnd = parseTimeToMs(sentences.get(sentences.size() - 1).getEndTime());
        scene.setDuration(lastEnd - firstBegin);

        // 生成动画步骤（每个句子一个 step，在对应的 beginTime 触发文本揭示）
        List<SceneStepVO> steps = new ArrayList<>();
        for (TtsUtil.TtsQueryResponse.Sentence sentence : sentences) {
            int at = parseTimeToMs(sentence.getBeginTime()) - firstBegin;
            steps.add(SceneStepVO.builder()
                    .at(at)
                    .action("text-reveal")
                    .payload(Map.of("text", sentence.getText()))
                    .build());
        }
        scene.setSteps(steps);
    }

    /** 将阿里云 TTS 时间字符串（毫秒）解析为 int */
    private int parseTimeToMs(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return 0;
        try {
            return Integer.parseInt(timeStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 构建 SSE item 事件的 JSON payload */
    private SseEvent buildItemEvent(SmartExplanationItemVO item) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("sceneIndex", item.getSceneIndex() != null ? item.getSceneIndex() : 0);
            payload.put("scene", item.getScene());
            return SseEvent.named("item", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("序列化场景失败", e);
            return SseEvent.named("error", "{\"message\":\"序列化失败\"}");
        }
    }

    // ==================== 流式 JSON 解析（不变） ====================

    /**
     * 把流式 token 中的 JSON 对象逐个提取出来。
     * 兼容 NDJSON（{"speech":...}\n{"speech":...}）和数组（[{...},{...}]）两种格式。
     */
    private Flux<SmartExplanationItemVO> extractItemsFromStream(Flux<String> chunks) {
        return Flux.create(sink -> {
            StringBuilder currentJson = new StringBuilder();
            StringBuilder rawText = new StringBuilder(); // 诊断：累积原始文本
            ParseState state = new ParseState();
            AtomicInteger extractedCount = new AtomicInteger(0);
            AtomicInteger chunkCount = new AtomicInteger(0);
            // 诊断：统计非字符串内的花括号
            AtomicInteger openBraceCount = new AtomicInteger(0);
            AtomicInteger closeBraceCount = new AtomicInteger(0);
            AtomicInteger braceDepthLogCount = new AtomicInteger(0);
            AtomicInteger quoteLogCount = new AtomicInteger(0);

            var subscription = chunks.subscribe(
                    chunk -> {
                        int idx = chunkCount.incrementAndGet();
                        // 诊断日志：前 3 个 chunk 用 INFO 级别输出
                        if (idx <= 3) {
                            log.info("LLM chunk #{} (长度={}): {}", idx, chunk.length(),
                                    chunk.substring(0, Math.min(chunk.length(), 300)));
                        }
                        // 累积原始文本（最多保留前 2000 字符用于诊断）
                        if (rawText.length() < 2000) {
                            rawText.append(chunk, 0, Math.min(chunk.length(), 2000 - rawText.length()));
                        }

                        for (int i = 0; i < chunk.length(); i++) {
                            char c = chunk.charAt(i);

                            if (!state.inObject) {
                                if (c == '{') {
                                    state.inObject = true;
                                    currentJson.setLength(0);
                                    currentJson.append(c);
                                    state.braceDepth = 1;
                                    state.inString = false;
                                    state.escape = false;
                                }
                                continue;
                            }

                            currentJson.append(c);

                            if (state.inString) {
                                if (state.escape) {
                                    state.escape = false;
                                } else if (c == '\\') {
                                    state.escape = true;
                                } else if (c == '"') {
                                    state.inString = false;
                                    // 诊断：记录引号关闭，前 30 次
                                    if (quoteLogCount.incrementAndGet() <= 30) {
                                        int start = Math.max(0, currentJson.length() - 30);
                                        log.info("诊断: \"关闭字符串\" 紧前方30字符: [{}]",
                                                currentJson.substring(start));
                                    }
                                }
                            } else {
                                if (c == '"') {
                                    state.inString = true;
                                    // 诊断：记录引号打开，前 30 次
                                    if (quoteLogCount.incrementAndGet() <= 30) {
                                        int start = Math.max(0, currentJson.length() - 30);
                                        log.info("诊断: \"打开字符串\" 紧前方30字符: [{}]",
                                                currentJson.substring(start));
                                    }
                                } else if (c == '{') {
                                    state.braceDepth++;
                                    openBraceCount.incrementAndGet();
                                    // 诊断：前 10 次花括号变化
                                    if (braceDepthLogCount.incrementAndGet() <= 10) {
                                        log.info("诊断: '{{' braceDepth={} (open#{})", 
                                                state.braceDepth, openBraceCount.get());
                                    }
                                } else if (c == '}') {
                                    state.braceDepth--;
                                    closeBraceCount.incrementAndGet();
                                    // 诊断：前 10 次花括号变化
                                    if (braceDepthLogCount.incrementAndGet() <= 10) {
                                        log.info("诊断: '}}' braceDepth={} (close#{})", 
                                                state.braceDepth, closeBraceCount.get());
                                    }
                                    if (state.braceDepth == 0) {
                                        String json = currentJson.toString().trim();
                                        currentJson.setLength(0);
                                        state.inObject = false;
                                        state.inString = false;
                                        state.escape = false;

                                        try {
                                            String cleanedJson = cleanJsonString(json);
                                            SmartExplanationItemVO item =
                                                    objectMapper.readValue(cleanedJson, SmartExplanationItemVO.class);
                                            sink.next(item);
                                            extractedCount.incrementAndGet();
                                            log.debug("成功提取场景 #{}: sceneIndex={}", 
                                                    extractedCount.get(), item.getSceneIndex());
                                        } catch (Exception e) {
                                            log.error("JSON解析失败，跳过该场景: {}", json, e);
                                        }
                                    }
                                } else if (c == '\n' && state.braceDepth > 0) {
                                    // NDJSON 容错：换行符是场景分隔符
                                    // LLM 可能漏掉最外层闭合 }，尝试补全后提取
                                    String json = currentJson.toString().trim();
                                    if (!json.isEmpty()) {
                                        StringBuilder fixed = new StringBuilder(json);
                                        for (int d = 0; d < state.braceDepth; d++) {
                                            fixed.append('}');
                                        }
                                        try {
                                            String cleanedJson = cleanJsonString(fixed.toString());
                                            SmartExplanationItemVO item =
                                                    objectMapper.readValue(cleanedJson, SmartExplanationItemVO.class);
                                            sink.next(item);
                                            extractedCount.incrementAndGet();
                                            log.info("NDJSON 容错提取场景 #{}: sceneIndex={}, 补全{}个}}",
                                                    extractedCount.get(), item.getSceneIndex(), state.braceDepth);
                                        } catch (Exception e) {
                                            log.warn("NDJSON 换行符处补全解析失败，跳过: braceDepth={}, json长度={}",
                                                    state.braceDepth, json.length());
                                        }
                                        // 无论成功失败，重置状态等待下一个 {
                                        currentJson.setLength(0);
                                        state.inObject = false;
                                        state.inString = false;
                                        state.escape = false;
                                        state.braceDepth = 0;
                                    }
                                }
                            }
                        }
                    },
                    sink::error,
                    () -> {
                        log.info("LLM 流处理完成：共收到 {} 个 content chunk，提取 {} 个场景", 
                                chunkCount.get(), extractedCount.get());
                        log.info("诊断: 非字符串花括号统计 open={} close={} 最终braceDepth={} inObject={} inString={}",
                                openBraceCount.get(), closeBraceCount.get(), state.braceDepth, state.inObject, state.inString);
                        if (currentJson.length() > 0 && state.braceDepth > 0) {
                            // 流结束时仍有未闭合的 JSON，尝试补全花括号后提取
                            String json = currentJson.toString().trim();
                            StringBuilder fixed = new StringBuilder(json);
                            for (int d = 0; d < state.braceDepth; d++) {
                                fixed.append('}');
                            }
                            try {
                                String cleanedJson = cleanJsonString(fixed.toString());
                                SmartExplanationItemVO item =
                                        objectMapper.readValue(cleanedJson, SmartExplanationItemVO.class);
                                sink.next(item);
                                extractedCount.incrementAndGet();
                                log.info("流结束容错提取场景 #{}: sceneIndex={}, 补全{}个}}",
                                        extractedCount.get(), item.getSceneIndex(), state.braceDepth);
                            } catch (Exception e) {
                                log.warn("流结束补全解析失败: braceDepth={}, json末尾200字符: {}",
                                        state.braceDepth,
                                        currentJson.substring(Math.max(0, currentJson.length() - 200)));
                            }
                        } else if (currentJson.length() > 0) {
                            log.warn("诊断: currentJson 残余长度={}，末尾200字符: {}", 
                                    currentJson.length(),
                                    currentJson.substring(Math.max(0, currentJson.length() - 200)));
                        }
                        if (extractedCount.get() == 0) {
                            log.warn("未提取到任何场景！LLM 原始输出前 2000 字符:\n{}", rawText.toString());
                        }
                        sink.complete();
                    }
            );

            // 修复：sink 取消时同时取消上游订阅
            sink.onCancel(() -> subscription.dispose());
            sink.onDispose(() -> subscription.dispose());

        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private String cleanJsonString(String json) {
        if (json == null || json.isEmpty()) return json;
        String cleaned = json;
        cleaned = cleaned.replaceAll("```json\\s*", "");
        cleaned = cleaned.replaceAll("```\\s*$", "");
        cleaned = cleaned.trim();
        cleaned = fixIllegalEscapes(cleaned);
        return cleaned;
    }

    private String fixIllegalEscapes(String json) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (!inString) {
                if (c == '"') inString = true;
                result.append(c);
            } else {
                if (escape) {
                    if (isValidEscape(c)) result.append(c);
                    else { result.append('\\'); result.append(c); }
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                    result.append(c);
                } else {
                    if (c == '"') inString = false;
                    result.append(c);
                }
            }
        }
        return result.toString();
    }

    private boolean isValidEscape(char c) {
        return c == '"' || c == '\\' || c == '/' || c == 'b' || c == 'f'
                || c == 'n' || c == 'r' || c == 't' || c == 'u';
    }

    private static class ParseState {
        boolean inObject = false;
        boolean inString = false;
        boolean escape = false;
        int braceDepth = 0;
    }
}
