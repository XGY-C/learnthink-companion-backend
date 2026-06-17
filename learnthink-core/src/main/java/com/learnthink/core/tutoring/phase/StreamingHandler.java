package com.learnthink.core.tutoring.phase;

import com.learnthink.core.tutoring.domain.*;
import com.learnthink.core.tutoring.diagram.DiagramOrchestrator;
import com.learnthink.core.tutoring.event.TutoringEventEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class StreamingHandler {
    private static final Logger log = LoggerFactory.getLogger(StreamingHandler.class);

    private static final Pattern SECTION_START = Pattern.compile("\\[SECTION:id=(\\w+),\\s*title=([^,]+),\\s*expandDefault=(true|false)\\]");
    private static final Pattern SECTION_END = Pattern.compile("\\[/SECTION\\]");
    private static final Pattern DIAGRAM_START = Pattern.compile(
        "\\[DIAGRAM:id=([^,]+),\\s*type=([^,]+),\\s*tool=([^,]+),\\s*priority=(\\d+),\\s*aspectRatio=\"([^\"]+)\"\\]");
    private static final Pattern DIAGRAM_END = Pattern.compile("\\[/DIAGRAM\\]");

    private final DiagramOrchestrator diagramOrchestrator;

    public StreamingHandler(DiagramOrchestrator diagramOrchestrator) {
        this.diagramOrchestrator = diagramOrchestrator;
    }

    public Map<String, String> handleStream(Flux<ChatResponse> stream, TutoringEventEmitter emitter,
                              ExecutionPlan plan) {
        StreamState state = new StreamState();

        // 阻塞订阅，保证调用方在流结束之前不会继续走 done/persistence
        try {
            stream
                .doOnNext(chunk -> processChunk(state, chunk, emitter))
                .doOnError(error -> {
                    log.error("Streaming error: {}", error.getMessage());
                    emitter.error("PHASE3_STREAM_FAILED", error.getMessage(), "3", true);
                })
                .blockLast();
        } finally {
            if (state.currentSectionId != null) {
                emitter.textSectionDone(state.currentSectionId);
                state.currentSectionId = null;
            }
        }
        return state.sectionContents;
    }

    private void emitChunk(StreamState state, TutoringEventEmitter emitter, String text) {
        if (state.currentSectionId == null || text == null || text.isEmpty()) return;
        emitter.textChunk(state.currentSectionId, text);
        state.sectionContents.merge(state.currentSectionId, text, String::concat);
    }

    private void processChunk(StreamState state, ChatResponse chunk, TutoringEventEmitter emitter) {
        String text = chunk.getResult().getOutput().getText();
        if (text == null || text.isEmpty()) return;
        state.accumulator.append(text);

        // 循环消费 accumulator，直到不再有可识别的标记或可发送的纯文本
        while (true) {
            String buf = state.accumulator.toString();
            if (buf.isEmpty()) return;

            if (state.inDiagram) {
                Matcher diagEnd = DIAGRAM_END.matcher(buf);
                if (diagEnd.find()) {
                    String diagramBlock = state.diagramBuffer.toString() + buf.substring(0, diagEnd.end());
                    state.diagramBuffer.setLength(0);
                    state.accumulator = new StringBuilder(buf.substring(diagEnd.end()));
                    state.inDiagram = false;
                    DiagramSpec spec = parseDiagramSpec(diagramBlock);
                    if (spec != null && state.currentSectionId != null) {
                        emitter.textDiagramSpec(spec.id(), state.currentSectionId,
                            new ExpectedDiagram(spec.id(), spec.type(), spec.description(),
                                spec.tool(), spec.priority(), spec.aspectRatio()));
                        diagramOrchestrator.generateAsync(spec, state.currentSectionId, emitter);
                        emitChunk(state, emitter, "[DIAGRAM_PLACEHOLDER:" + spec.id() + "]");
                    }
                    continue;
                }
                // DIAGRAM_END 未出现：把已有数据搬入 diagramBuffer，等待更多 chunk
                state.diagramBuffer.append(buf);
                state.accumulator.setLength(0);
                return;
            }

            Matcher diagStart = DIAGRAM_START.matcher(buf);
            Matcher secStart = SECTION_START.matcher(buf);
            Matcher secEnd = SECTION_END.matcher(buf);

            int diagIdx = diagStart.find() ? diagStart.start() : Integer.MAX_VALUE;
            int secStartIdx = secStart.find(0) ? secStart.start() : Integer.MAX_VALUE;
            int secEndIdx = secEnd.find(0) ? secEnd.start() : Integer.MAX_VALUE;

            int minIdx = Math.min(Math.min(diagIdx, secStartIdx), secEndIdx);

            // 没有任何标记 — 整段作为正文输出，但保留尾部可能未完成的"["，避免割裂标记
            if (minIdx == Integer.MAX_VALUE) {
                int safeEnd = safeFlushBoundary(buf);
                if (safeEnd <= 0) return;
                if (state.currentSectionId != null) {
                    emitChunk(state, emitter, buf.substring(0, safeEnd));
                }
                state.accumulator = new StringBuilder(buf.substring(safeEnd));
                return;
            }

            // 标记前的正文先输出
            if (minIdx > 0 && state.currentSectionId != null) {
                emitChunk(state, emitter, buf.substring(0, minIdx));
            }
            String tail = buf.substring(minIdx);
            state.accumulator = new StringBuilder(tail);

            // SECTION_START
            if (minIdx == secStartIdx && minIdx != Integer.MAX_VALUE) {
                Matcher m = SECTION_START.matcher(tail);
                if (m.find() && m.start() == 0) {
                    if (state.currentSectionId != null) {
                        emitter.textSectionDone(state.currentSectionId);
                    }
                    state.currentSectionId = m.group(1);
                    emitter.textSectionStart(state.currentSectionId, m.group(2).trim());
                    state.accumulator = new StringBuilder(tail.substring(m.end()));
                    continue;
                }
                return;
            }

            // SECTION_END
            if (minIdx == secEndIdx) {
                Matcher m = SECTION_END.matcher(tail);
                if (m.find() && m.start() == 0) {
                    if (state.currentSectionId != null) {
                        emitter.textSectionDone(state.currentSectionId);
                        state.currentSectionId = null;
                    }
                    state.accumulator = new StringBuilder(tail.substring(m.end()));
                    continue;
                }
                return;
            }

            // DIAGRAM_START
            if (minIdx == diagIdx) {
                state.inDiagram = true;
                continue; // 下轮 while 进入 inDiagram 分支
            }
        }
    }

    /**
     * 保留可能截断的标记前缀。若 buf 末尾出现 "[" 但尚未形成完整 token，
     * 截到 "[" 之前；否则全量可发。
     */
    private int safeFlushBoundary(String buf) {
        int lastBracket = buf.lastIndexOf('[');
        if (lastBracket < 0) return buf.length();
        String tail = buf.substring(lastBracket);
        // 若 "[" 后已经出现 "]" 说明不是标记前缀；否则保留它
        if (tail.indexOf(']') >= 0) return buf.length();
        return lastBracket;
    }

    private DiagramSpec parseDiagramSpec(String diagramBlock) {
        Matcher start = DIAGRAM_START.matcher(diagramBlock);
        if (start.find()) {
            String id = start.group(1);
            String type = start.group(2);
            String tool = start.group(3);
            int priority = Integer.parseInt(start.group(4));
            String aspectRatio = start.group(5);

            int descStart = start.end();
            Matcher end = DIAGRAM_END.matcher(diagramBlock);
            String description = "";
            if (end.find(descStart)) {
                description = diagramBlock.substring(descStart, end.start()).trim();
            }

            return new DiagramSpec(id, type, tool, priority, aspectRatio, description, null);
        }
        return null;
    }

    private static class StreamState {
        StringBuilder accumulator = new StringBuilder();
        String currentSectionId;
        boolean inDiagram;
        StringBuilder diagramBuffer = new StringBuilder();
        Map<String, String> sectionContents = new LinkedHashMap<>();
    }
}
