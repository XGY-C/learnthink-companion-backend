package com.learnthink.web.controller;

import com.learnthink.common.dto.question.*;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.QuestionBankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
public class QuestionBankController {

    private final QuestionBankService questionBankService;

    @GetMapping("/questions")
    public Result<QuestionPageDTO> listQuestions(
            @RequestParam String courseId,
            @RequestParam(required = false) String questionType,
            @RequestParam(required = false) Integer difficulty,
            @RequestParam(required = false) String kpId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(questionBankService.listQuestions(userId, courseId,
                questionType, difficulty, kpId, status, sort, page, size));
    }

    @GetMapping("/questions/{id}")
    public Result<QuestionDTO> getQuestion(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(questionBankService.getQuestionDetail(id, userId));
    }

    @PostMapping("/questions")
    public Result<QuestionDTO> createQuestion(@RequestBody CreateQuestionRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(questionBankService.createQuestion(userId, req));
    }

    @PostMapping("/questions/batch")
    public Result<BatchCreateQuestionsResultDTO> batchCreate(@RequestBody BatchCreateQuestionsRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(questionBankService.batchCreateFromResource(userId, req));
    }

    @PutMapping("/questions/{id}")
    public Result<QuestionDTO> updateQuestion(@PathVariable String id,
                                               @RequestBody CreateQuestionRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(questionBankService.updateQuestion(id, userId, req));
    }

    @DeleteMapping("/questions/{id}")
    public Result<Void> deleteQuestion(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        questionBankService.deleteQuestion(id, userId);
        return Result.success();
    }

    @PostMapping("/questions/answer")
    public Result<AnswerResultDTO> submitAnswer(@RequestBody SubmitAnswerRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(questionBankService.submitAnswer(userId, req.getCourseId(), req));
    }

    @GetMapping("/questions/stats/kp-accuracy")
    public Result<List<KpAccuracyDTO>> getKpAccuracy(@RequestParam String courseId) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(questionBankService.getKpAccuracy(userId, courseId));
    }

    @GetMapping("/questions/wrong")
    public Result<QuestionPageDTO> listWrongQuestions(
            @RequestParam String courseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(questionBankService.listWrongQuestions(userId, courseId, page, size));
    }

}
