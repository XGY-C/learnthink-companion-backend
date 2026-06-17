package com.learnthink.web.controller;

import com.learnthink.common.dto.CodeRunRequest;
import com.learnthink.common.dto.CodeRunResult;
import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.result.Result;
import com.learnthink.core.service.Judge0Client;
import com.learnthink.core.util.LanguageMapper;
import com.learnthink.common.util.UserContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/code")
@RequiredArgsConstructor
public class CodeController {

    private final Judge0Client judge0Client;

    private static final List<Pattern> BLACKLIST = List.of(
        Pattern.compile("System\\.exit"),
        Pattern.compile("Runtime\\.exec"),
        Pattern.compile("ProcessBuilder"),
        Pattern.compile("__import__\\s*\\(\\s*['\"]os['\"]\\s*\\)")
    );

    @PostMapping("/run")
    public Result<CodeRunResult> runCode(@RequestBody CodeRunRequest req) {
        UserContextUtil.getCurrentUserId();

        String code = req.getSourceCode();
        if (code == null || code.isBlank()) {
            throw new BusinessException("代码不能为空");
        }
        if (code.length() > 50000) {
            throw new BusinessException("代码长度超过限制 (50KB)");
        }
        for (Pattern pattern : BLACKLIST) {
            if (pattern.matcher(code).find()) {
                throw new BusinessException("代码包含不允许的模式: " + pattern.pattern());
            }
        }

        int langId = LanguageMapper.toJudge0Id(req.getLanguage());
        CodeRunResult result = judge0Client.submit(
            code, langId, req.getStdin(),
            req.getCpuTimeLimit() != null ? req.getCpuTimeLimit() : 5,
            req.getMemoryLimit() != null ? req.getMemoryLimit() : 256000
        );

        return Result.success(result);
    }
}
