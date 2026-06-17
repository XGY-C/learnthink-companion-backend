package com.learnthink.core.service.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.profile.BehaviorAccumulatorDto;
import com.learnthink.common.dto.profile.PendingConfirmationDto;
import com.learnthink.core.domain.entity.ProfileSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProfileStep1Filter {

    private static final Logger log = LoggerFactory.getLogger(ProfileStep1Filter.class);
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "={3,}\\s*(PROFILE_SENTENCES|SIGNALS|PENDING_CONFIRMATIONS|ACCUMULATING_BEHAVIORS)\\s*={3,}",
            Pattern.MULTILINE);
    private static final Pattern SUBSECTION_PATTERN = Pattern.compile(
            "\\[(user_said|user_corrected|behavior|learning_result|llm_inferred)\\]");

    private final ObjectMapper objectMapper;

    public ProfileStep1Filter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public FilteredResult filter(String rawStep1Output) {
        if (rawStep1Output == null || rawStep1Output.isBlank()) {
            log.warn("Step 1 output is empty, returning empty FilteredResult");
            return new FilteredResult("", Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        String sentences = extractSection(rawStep1Output, "PROFILE_SENTENCES");
        String rawSignals = extractSection(rawStep1Output, "SIGNALS");
        String rawPending = extractSection(rawStep1Output, "PENDING_CONFIRMATIONS");
        String rawBehaviors = extractSection(rawStep1Output, "ACCUMULATING_BEHAVIORS");

        String cleanSentences = stripLlmInferred(sentences);

        if (cleanSentences.isBlank()) {
            log.info("After stripping llm_inferred, PROFILE_SENTENCES is empty — skipping Step 2");
        }

        List<ProfileSignal> signals = parseJsonArray(rawSignals, new TypeReference<List<ProfileSignal>>() {});
        List<PendingConfirmationDto> pendingConfirmations = parseJsonArray(rawPending, new TypeReference<List<PendingConfirmationDto>>() {});
        List<BehaviorAccumulatorDto> accumulatingBehaviors = parseJsonArray(rawBehaviors, new TypeReference<List<BehaviorAccumulatorDto>>() {});

        return new FilteredResult(cleanSentences, signals, pendingConfirmations, accumulatingBehaviors);
    }

    private String extractSection(String text, String sectionName) {
        Matcher matcher = SECTION_PATTERN.matcher(text);
        String targetSection = null;
        while (matcher.find()) {
            if (sectionName.equals(matcher.group(1))) {
                targetSection = matcher.group();
                int start = matcher.end();
                if (matcher.find()) {
                    return text.substring(start, matcher.start()).trim();
                }
                return text.substring(start).trim();
            }
        }
        log.debug("Section {} not found, returning empty", sectionName);
        return "";
    }

    private String stripLlmInferred(String sentences) {
        if (sentences == null || sentences.isBlank()) {
            return "";
        }

        String[] parts = SUBSECTION_PATTERN.split(sentences);
        if (parts.length <= 1) {
            return sentences;
        }

        Matcher matcher = SUBSECTION_PATTERN.matcher(sentences);
        List<String> headers = new ArrayList<>();
        while (matcher.find()) {
            headers.add(matcher.group(1));
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < headers.size(); i++) {
            if ("llm_inferred".equals(headers.get(i))) {
                continue;
            }
            if (i < parts.length) {
                String content = parts[i].trim();
                if (!content.isEmpty() && !content.equals("(无)")) {
                    result.append("[").append(headers.get(i)).append("]\n").append(content).append("\n\n");
                }
            }
        }

        return result.toString().trim();
    }

    private <T> List<T> parseJsonArray(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        // Strip markdown code fences if present
        String cleaned = json.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).strip();
            }
        }
        // Extract the first JSON array if surrounded by extra text
        int bracketStart = cleaned.indexOf('[');
        int bracketEnd = cleaned.lastIndexOf(']');
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            cleaned = cleaned.substring(bracketStart, bracketEnd + 1);
        }
        try {
            return objectMapper.readValue(cleaned, typeRef);
        } catch (Exception e) {
            log.warn("Failed to parse JSON array section, returning empty list: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public record FilteredResult(
            String cleanSentences,
            List<ProfileSignal> signals,
            List<PendingConfirmationDto> pendingConfirmations,
            List<BehaviorAccumulatorDto> accumulatingBehaviors
    ) {}
}
