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
                        .content()
                        .transform(this::extractItemsFromStream)
                        .flatMapSequential(item -> processItemWithTts(item), 4)
                        .onErrorResume(err -> {
                            log.error("TTS 处理失败，跳过该场景", err);
                            return Mono.empty();
                        }),
                Flux.just(SseEvent.named("done", "{\"message\":\"回答完成\"}"))
        ).onErrorResume(error -> {
            log.error("视频讲解过程发生错误", error);
            String msg = error.getMessage() != null ? error.getMessage() : "未知错误";
            return Flux.just(SseEvent.named("error", "{\"message\":\"处理失败: " + msg + "\"}"));
        });
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
            ParseState state = new ParseState();

            chunks.subscribe(
                    chunk -> {
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
                                }
                            } else {
                                if (c == '"') {
                                    state.inString = true;
                                } else if (c == '{') {
                                    state.braceDepth++;
                                } else if (c == '}') {
                                    state.braceDepth--;
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
                                        } catch (Exception e) {
                                            log.error("JSON解析失败，跳过该场景: {}", json, e);
                                            // 不中断流，继续处理后续场景
                                        }
                                    }
                                }
                            }
                        }
                    },
                    sink::error,
                    sink::complete
            );
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
