package com.learnthink.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeRunResult {
    private String stdout;
    private String stderr;
    private String compileOutput;
    private String status;
    private int statusCode;
    private Double time;
    private Double memory;
}
