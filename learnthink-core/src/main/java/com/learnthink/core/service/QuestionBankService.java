package com.learnthink.core.service;

import com.learnthink.common.dto.question.*;

import java.util.List;

public interface QuestionBankService {
    QuestionPageDTO listQuestions(String userId, String courseId, String questionType,
                                  Integer difficulty, String kpId, String status,
                                  String sort, int page, int size);

    QuestionDTO getQuestionDetail(String id, String userId);

    QuestionDTO createQuestion(String userId, CreateQuestionRequest req);

    BatchCreateQuestionsResultDTO batchCreateFromResource(String userId, BatchCreateQuestionsRequest req);

    QuestionDTO updateQuestion(String id, String userId, CreateQuestionRequest req);

    void deleteQuestion(String id, String userId);

    AnswerResultDTO submitAnswer(String userId, String courseId, SubmitAnswerRequest req);

    List<KpAccuracyDTO> getKpAccuracy(String userId, String courseId);

    QuestionPageDTO listWrongQuestions(String userId, String courseId, int page, int size);
}
