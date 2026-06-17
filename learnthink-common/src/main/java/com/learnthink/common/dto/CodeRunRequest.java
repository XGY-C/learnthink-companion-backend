package com.learnthink.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CodeRunRequest {
    @JsonProperty("source_code")
    private String sourceCode;

    private String language;

    private String stdin;

    @JsonProperty("expected_output")
    private String expectedOutput;

    @JsonProperty("cpu_time_limit")
    private Integer cpuTimeLimit = 5;

    @JsonProperty("memory_limit")
    private Integer memoryLimit = 256000;
}
