package com.learnthink.core.util;

import java.util.Map;

public class LanguageMapper {
    private static final Map<String, Integer> MAP = Map.of(
        "python", 71,
        "javascript", 63,
        "java", 62,
        "cpp", 54,
        "go", 60,
        "rust", 73
    );

    private LanguageMapper() {}

    public static int toJudge0Id(String language) {
        return MAP.getOrDefault(language.toLowerCase(), 71);
    }

    public static String toExtension(String language) {
        return switch (language.toLowerCase()) {
            case "python" -> ".py";
            case "javascript" -> ".js";
            case "java" -> ".java";
            case "cpp" -> ".cpp";
            case "go" -> ".go";
            case "rust" -> ".rs";
            default -> ".py";
        };
    }
}
