package com.learnthink.core.directanswer.agent;

import com.learnthink.core.directanswer.domain.response.AnalysisResult;
import com.learnthink.core.directanswer.domain.response.PrerequisiteAnchor;
import com.learnthink.core.directanswer.domain.response.SectionBlueprint;
import com.learnthink.core.directanswer.event.DirectAnswerEventEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * Phase3: SectionGeneratorAgent — 推理模型流式生成（双通道：思考 + 内容）。
 * 使用推理模型（deepseek-reasoner），单次大调用 + 流式双通道。
 * 支持三级容错：精确标记匹配 → 宽松正则匹配 → 推送 raw text。
 *
 * v2 升级：真正的反应式流式处理，使用 doOnNext + blockLast 逐 LLM chunk 推送，
 * 而非 collectList 全量收集后一次性处理。配合 FlushableSseEmitter 实现端到端真流式。
 */
@Component
public class SectionGeneratorAgent {
    private static final Logger log = LoggerFactory.getLogger(SectionGeneratorAgent.class);

    /** 段落标记格式 */
    private static final String MARKER_OPEN = "[%s]";
    private static final String MARKER_CLOSE = "[/%s]";
    /** 思考标记 */
    private static final String THINK_START = "【思考】";
    private static final String THINK_END = "【/思考】";

    private static final String SYSTEM_PROMPT = """
        你是优秀数学教师，请按指定结构生成完整解答。
        输出必须使用标记格式：
         - 思考过程用 【思考】...【/思考】 包裹
         - 每个段落用 [段落ID]...[/段落ID] 包裹

        段落顺序：
        1. [answer_hero]...[/answer_hero] — 最终答案（简洁明确，含单位/格式）
        2. [problem_analysis]...[/problem_analysis]
        3. [strategy_overview]...[/strategy_overview] — 解题策略（整体思路、关键洞察）
        4. [reasoning_chain]...[/reasoning_chain]
        5. [method_summary]...[/method_summary] — 方法总结（核心方法、口诀、条件）
        6. [error_warning]...[/error_warning] — 易错提醒（常见错误、变式题）
        7. [prerequisite_knowledge]...[/prerequisite_knowledge] — 前置知识树（递归结构）

        重要：第2、4段落需要在末尾附上结构化JSON代码块（用 ```json ``` 包裹），格式如下：

        第2段 [problem_analysis] 末尾必须附加：
        ```json
        {
          "conditionMappings": [
            {"label":"数据","value":"x=[1,2,3,4,5]","highlightType":"data","interpretation":"输入特征"},
            {"label":"已知","value":"y=[3,5,7,9,11]","highlightType":"known","interpretation":"对应标签"},
            {"label":"待求","value":"w和b","highlightType":"target","interpretation":"模型参数"}
          ],
          "overallSummary": "一句话概括题目",
          "difficulty": "easy|medium|hard"
        }
        ```
        highlightType 取值：data(黄色)/known(蓝色)/target(青绿)/distractor(灰色干扰项)

        第4段 [reasoning_chain] 末尾必须附加：
        ```json
        {
          "steps": [
            {"stepIndex":1,"title":"步骤标题","thinking":"思路","expression":"表达式","result":"结果","type":"core|normal","intention":"为什么这一步有效"}
          ]
        }
        ```
        type 取值：core(核心步骤,琥珀金)/normal(普通步骤,灰色)

        要求：
        - 每个段落内容不要重复（不要写两遍）
        - 思考过程要展示推理思路
        - 每个段落内容详实、适合学生阅读
        - Markdown 格式美观""";

    private final ChatClient chatClient;

    public SectionGeneratorAgent(@Qualifier("reasoningChatClientBuilder") ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 流式生成解答。
     */
    public GenerationResult generateStream(AnalysisResult analysis,
                                           List<SectionBlueprint> blueprints,
                                           String question,
                                           DirectAnswerEventEmitter emitter) {
        log.info("SectionGeneratorAgent generating {} sections for: {}", blueprints.size(), truncate(question, 50));

        StringBuilder sectionList = new StringBuilder();
        for (SectionBlueprint bp : blueprints) {
            sectionList.append(String.format("- %s: %s\n", bp.id(), bp.title()));
        }

        String userPrompt = String.format(
            "题目：%s\n题型：%s\n学科：%s\n\n请按以下结构生成解答：\n%s",
            question, analysis.problemType(), analysis.subject(), sectionList);

        try {
            Flux<ChatResponse> stream = chatClient.prompt()
                .messages(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userPrompt))
                .stream()
                .chatResponse();

            return processStreamWithMarkers(stream, blueprints, emitter);
        } catch (Exception e) {
            log.error("SectionGeneratorAgent failed: {}", e.getMessage(), e);
            emitter.error("GENERATION_FAILED", "生成失败：" + e.getMessage(), true);
            emitter.complete();
            return new GenerationResult(Map.of(), Map.of(), List.of(), "");
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  反应式流式解析（v2：doOnNext + blockLast，替换 collectList）
    // ═══════════════════════════════════════════════════════════

    /**
     * 处理带标记的流式输出（双通道分离）—— 真正的反应式流式处理。
     * 对比辅导模式 StreamingHandler：使用 doOnNext + blockLast 逐块处理，而非 collectList 全量收集。
     */
    private GenerationResult processStreamWithMarkers(Flux<ChatResponse> stream,
                                                       List<SectionBlueprint> blueprints,
                                                       DirectAnswerEventEmitter emitter) {
        Map<String, StringBuilder> sectionContent = new LinkedHashMap<>();
        Map<String, Object> sectionStructured = new LinkedHashMap<>();
        List<PrerequisiteAnchor> anchors = new ArrayList<>();
        StringBuilder thoughtBuffer = new StringBuilder();

        for (SectionBlueprint bp : blueprints) {
            sectionContent.put(bp.id(), new StringBuilder());
        }

        StreamParseState state = new StreamParseState(blueprints);

        try {
            stream
                .doOnNext(chatResponse -> {
                    if (chatResponse.getResult() == null) return;
                    var output = chatResponse.getResult().getOutput();
                    if (output == null) return;

                    // 从 metadata 提取 DeepSeek reasoning_content（推理模型专用）
                    if (output.getMetadata() != null) {
                        String reasoning = (String) output.getMetadata().get("reasoning_content");
                        if (reasoning != null && !reasoning.isEmpty()) {
                            thoughtBuffer.append(reasoning);
                            emitter.thought(reasoning);
                        }
                    }

                    // 文本内容走标记解析（【思考】标记、section 标记）
                    String text = output.getText();
                    if (text != null && !text.isEmpty()) {
                        processChunk(text, state, emitter, sectionContent, thoughtBuffer);
                    }
                })
                .doOnComplete(() -> {
                    // 流结束：先把 accumulator 中残留内容强制消费到当前 section
                    if (state.accumulator.length() > 0 && state.currentSectionId != null) {
                        drainAccumulator(state, emitter, sectionContent);
                    }
                    // 关闭最后一个 section
                    if (state.currentSectionId != null) {
                        finishCurrentSection(state, emitter);
                    }
                })
                .doOnError(e -> {
                    log.error("SectionGeneratorAgent stream error: {}", e.getMessage(), e);
                    emitter.error("STREAM_ERROR", "流式生成中断：" + e.getMessage(), true);
                    emitter.complete();
                })
                .blockLast();
        } catch (Exception e) {
            log.error("SectionGeneratorAgent processing error: {}", e.getMessage(), e);
            emitter.error("GENERATION_FAILED", "生成失败：" + e.getMessage(), true);
            emitter.complete();
            return new GenerationResult(Map.of(), Map.of(), List.of(), "");
        }

        flushSectionContent(state, sectionContent);

        Map<String, String> contents = new LinkedHashMap<>();
        for (SectionBlueprint bp : blueprints) {
            contents.put(bp.id(), sectionContent.getOrDefault(bp.id(), new StringBuilder()).toString());
        }

        return new GenerationResult(contents, sectionStructured, anchors, thoughtBuffer.toString());
    }

    /**
     * 处理单个 LLM 流式块。维护 accumulator 防止标记被跨块截断。
     */
    private void processChunk(String rawChunk, StreamParseState state,
                              DirectAnswerEventEmitter emitter,
                              Map<String, StringBuilder> sectionContent,
                              StringBuilder thoughtBuffer) {
        state.accumulator.append(rawChunk);

        while (true) {
            String text = state.accumulator.toString();
            if (text.isEmpty()) return;

            boolean consumed = false;

            // ── 检查思考模式标记 ──
            if (!state.inThink) {
                if (text.startsWith(THINK_START, state.parseIdx)) {
                    state.inThink = true;
                    state.parseIdx += THINK_START.length();
                    consumed = true;
                }
            } else {
                if (text.startsWith(THINK_END, state.parseIdx)) {
                    state.inThink = false;
                    state.parseIdx += THINK_END.length();
                    consumed = true;
                }
            }

            // ── 思考模式：批量推送 thought（按 LLM chunk 粒度，避免逐字符 SSE 风暴）──
            if (state.inThink) {
                int end = text.indexOf(THINK_END, state.parseIdx);
                int chunkEnd;
                if (end >= 0) {
                    // 找到思考结束标记：推送到标记之前
                    chunkEnd = end;
                } else {
                    // 未找到结束标记：保留末尾可能被跨 chunk 截断的标记前缀
                    int safeLen = THINK_END.length() - 1;
                    chunkEnd = Math.max(state.parseIdx, text.length() - safeLen);
                }
                if (chunkEnd > state.parseIdx) {
                    String chunk = text.substring(state.parseIdx, chunkEnd);
                    thoughtBuffer.append(chunk);
                    emitter.thought(chunk);
                    state.parseIdx = chunkEnd;
                    consumed = true;
                }
                // 末尾保留的标记前缀无法消费时，等待下一个 LLM chunk 追加数据
                if (end < 0 && chunkEnd <= state.parseIdx) {
                    return;
                }
                if (state.parseIdx >= text.length()) {
                    compactAccumulator(state);
                    return;
                }
            }

            // ── 检查 Section 开始标记 ──
            if (!state.inThink && state.currentSectionId == null) {
                String marker = tryMatchSectionMarker(text, state.parseIdx, state.blueprints);
                if (marker != null) {
                    String markerStr = String.format(MARKER_OPEN, marker);
                    state.currentSectionId = marker;
                    String title = getSectionTitle(state.blueprints, marker);
                    emitter.sectionStart(marker, title);
                    state.parseIdx += markerStr.length();
                    consumed = true;
                    continue;
                }
                // 未匹配到开始标记：当前位置可能是被跨 chunk 截断的标记前缀，或者是标记间的空白。
                // 如果当前位置是 '[' 且是某个开始标记的前缀，停止等待下一个 chunk。
                // 否则跳过非标记字符（如换行、空格），避免解析器卡在标记间空白上。
                if (state.parseIdx < text.length()) {
                    char c = text.charAt(state.parseIdx);
                    if (c == '[' && isPossibleSectionMarkerPrefix(text, state.parseIdx, state.blueprints)) {
                        // 可能是截断的开始标记，等待下一个 chunk
                        break;
                    }
                    if (!Character.isWhitespace(c)) {
                        // 非空白且非标记：可能是 LLM 在标记外输出了内容，跳过避免卡死
                        log.debug("Skipping non-marker content outside section: '{}'", c);
                    }
                    state.parseIdx++;
                    consumed = true;
                    continue;
                }
            }

            // ── 检查 Section 关闭标记（支持 [/xxx] 和 </xxx> 两种格式）──
            if (!state.inThink && state.currentSectionId != null) {
                int closeLen = matchCloseMarker(text, state.parseIdx, state.currentSectionId);
                if (closeLen > 0) {
                    if (state.sectionTextAccum.length() > 0) {
                        String chunkText = state.sectionTextAccum.toString();
                        emitter.sectionChunk(state.currentSectionId, chunkText);
                        state.sectionTextAccum.setLength(0);
                    }
                    emitter.sectionDone(state.currentSectionId);
                    state.parseIdx += closeLen;
                    state.currentSectionId = null;
                    consumed = true;
                    continue;
                }
            }

            // ── 普通内容：累积到 sectionTextAccum，按 LLM 块粒度发送 ──
            if (!state.inThink && state.currentSectionId != null) {
                int safeEnd = safeSectionBoundary(text, state.parseIdx, state.currentSectionId);
                if (safeEnd > state.parseIdx) {
                    String content = text.substring(state.parseIdx, safeEnd);
                    state.sectionTextAccum.append(content);
                    state.parseIdx = safeEnd;

                    if (state.sectionTextAccum.length() > 0) {
                        emitter.sectionChunk(state.currentSectionId, state.sectionTextAccum.toString());
                        if (sectionContent.containsKey(state.currentSectionId)) {
                            sectionContent.get(state.currentSectionId).append(state.sectionTextAccum);
                        }
                        state.sectionTextAccum.setLength(0);
                    }
                    consumed = true;
                }
                // safeEnd == parseIdx：当前位置可能是被跨 chunk 截断的关闭标记前缀，
                // 不强制消费，break 等待下一个 chunk 追加完整数据
            }

            if (!consumed) break;
            compactAccumulator(state);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 紧缩累加器：丢弃已解析的头部。
     */
    private void compactAccumulator(StreamParseState state) {
        if (state.parseIdx > 0 && state.parseIdx >= state.accumulator.length()) {
            state.accumulator.setLength(0);
            state.parseIdx = 0;
        }
    }

    /**
     * 查找当前 section 内容的"安全发送边界"。
     * 只关心当前 section 的关闭标记 [/xxx]，遇到则停在 `[` 之前。
     * 其他 section 的标记出现在正文中（如 LLM 提到的结构说明）当作普通文本放行。
     * 关键：遇到 `[` 且是关闭标记的前缀时也停止，等待下一个 chunk 追加，
     * 防止跨 chunk 截断的关闭标记被当作普通文本消费掉。
     */
    private int safeSectionBoundary(String text, int from, String currentSectionId) {
        if (from >= text.length()) return from;

        int pos = from;
        String bracketClose = "[/" + currentSectionId + "]";
        String angleClose = "</" + currentSectionId + ">";
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '[' || c == '<') {
                String remaining = text.substring(pos);
                // 完整关闭标记（方括号或尖括号），停止
                if (remaining.startsWith(bracketClose)) break;
                if (remaining.startsWith(angleClose)) break;
                // 检查是否是关闭标记被跨 chunk 截断的前缀
                if (bracketClose.startsWith(remaining)) break;
                if (angleClose.startsWith(remaining)) break;
            }
            pos++;
        }
        return pos;
    }

    /**
     * 流结束时，将 accumulator 中未消费的残留内容强制推到当前 section。
     */
    private void drainAccumulator(StreamParseState state, DirectAnswerEventEmitter emitter,
                                  Map<String, StringBuilder> sectionContent) {
        if (state.accumulator.length() == 0 || state.currentSectionId == null) return;

        // 仅消费 parseIdx 之后的未解析内容，避免重复推送已消费部分
        int start = Math.min(state.parseIdx, state.accumulator.length());
        String remaining = state.accumulator.substring(start);
        // 查找关闭标记（支持 [/xxx] 和 </xxx> 两种格式）
        String bracketClose = "[/" + state.currentSectionId + "]";
        String angleClose = "</" + state.currentSectionId + ">";
        int bracketIdx = remaining.indexOf(bracketClose);
        int angleIdx = remaining.indexOf(angleClose);
        int closeIdx = -1;
        if (bracketIdx >= 0 && angleIdx >= 0) closeIdx = Math.min(bracketIdx, angleIdx);
        else if (bracketIdx >= 0) closeIdx = bracketIdx;
        else if (angleIdx >= 0) closeIdx = angleIdx;
        if (closeIdx >= 0) {
            remaining = remaining.substring(0, closeIdx);
        }
        remaining = remaining.replace("【思考】", "").replace("【/思考】", "").trim();
        if (!remaining.isEmpty()) {
            emitter.sectionChunk(state.currentSectionId, remaining);
            if (sectionContent.containsKey(state.currentSectionId)) {
                sectionContent.get(state.currentSectionId).append(remaining);
            }
        }
        state.accumulator.setLength(0);
        state.parseIdx = 0;
        // 清空文本累积器，防止 finishCurrentSection 重复推送残留内容
        state.sectionTextAccum.setLength(0);
    }

    /**
     * 完成当前 section（发送 section_done，清空文本累积器）。
     */
    private void finishCurrentSection(StreamParseState state, DirectAnswerEventEmitter emitter) {
        if (state.currentSectionId == null) return;
        if (state.sectionTextAccum.length() > 0) {
            emitter.sectionChunk(state.currentSectionId, state.sectionTextAccum.toString());
            state.sectionTextAccum.setLength(0);
        }
        emitter.sectionDone(state.currentSectionId);
        state.currentSectionId = null;
    }

    /**
     * 将 sectionTextAccum 中的剩余内容合并到 sectionContent。
     */
    private void flushSectionContent(StreamParseState state,
                                      Map<String, StringBuilder> sectionContent) {
        if (state.currentSectionId != null
            && state.sectionTextAccum.length() > 0
            && sectionContent.containsKey(state.currentSectionId)) {
            sectionContent.get(state.currentSectionId).append(state.sectionTextAccum);
        }
    }

    /**
     * 检测 text 从 pos 开始是否匹配当前 section 的关闭标记。
     * 支持 [/xxx]（方括号）和 </xxx>（尖括号）两种格式。
     * @return 匹配则返回标记长度，不匹配返回 0
     */
    private int matchCloseMarker(String text, int pos, String sectionId) {
        String bracketClose = "[/" + sectionId + "]";
        if (text.startsWith(bracketClose, pos)) return bracketClose.length();
        String angleClose = "</" + sectionId + ">";
        if (text.startsWith(angleClose, pos)) return angleClose.length();
        return 0;
    }

    /**
     * 检查 text 从 pos 开始的剩余部分是否是某个开始标记的前缀（可能被跨 chunk 截断）。
     */
    private boolean isPossibleSectionMarkerPrefix(String text, int pos, List<SectionBlueprint> blueprints) {
        if (pos >= text.length()) return false;
        String remaining = text.substring(pos);
        for (SectionBlueprint bp : blueprints) {
            String marker = String.format(MARKER_OPEN, bp.id());
            if (marker.startsWith(remaining)) return true;
        }
        return false;
    }

    /**
     * 尝试匹配段落开始标记，返回段落 ID（支持精确和大小写不敏感）。
     */
    private String tryMatchSectionMarker(String text, int pos, List<SectionBlueprint> blueprints) {
        for (SectionBlueprint bp : blueprints) {
            String marker = String.format(MARKER_OPEN, bp.id());
            if (text.startsWith(marker, pos)) return bp.id();
            // 宽松模糊匹配
            String fuzzyMarker = String.format(MARKER_OPEN, bp.id().toLowerCase());
            String textLower = text.substring(pos, Math.min(pos + fuzzyMarker.length(), text.length())).toLowerCase();
            if (textLower.equals(fuzzyMarker)) {
                int actualLen = text.substring(pos).indexOf('[') + bp.id().length() + 1;
                if (actualLen > 0) return bp.id();
            }
        }
        return null;
    }

    private String getSectionTitle(List<SectionBlueprint> blueprints, String sectionId) {
        return blueprints.stream()
            .filter(b -> b.id().equals(sectionId))
            .map(SectionBlueprint::title)
            .findFirst()
            .orElse(sectionId);
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    // ═══════════════════════════════════════════════════════════
    //  内部类型
    // ═══════════════════════════════════════════════════════════

    /**
     * 流式解析状态。
     */
    private static class StreamParseState {
        final List<SectionBlueprint> blueprints;
        final StringBuilder accumulator = new StringBuilder();
        final StringBuilder sectionTextAccum = new StringBuilder();
        int parseIdx = 0;
        boolean inThink = false;
        String currentSectionId = null;

        StreamParseState(List<SectionBlueprint> blueprints) {
            this.blueprints = blueprints;
        }
    }

    /**
     * 生成结果。
     */
    public record GenerationResult(
        Map<String, String> sectionContents,
        Map<String, Object> sectionStructured,
        List<PrerequisiteAnchor> anchors,
        String thoughtContent
    ) {}
}
