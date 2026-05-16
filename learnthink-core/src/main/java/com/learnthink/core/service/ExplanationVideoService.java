package com.learnthink.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.learnthink.core.domain.dto.ProjectInput;
import com.learnthink.core.domain.dto.ExplanationVideoDTO;

public interface ExplanationVideoService {

    /**
     * 生成视频
     * @param projectInput 视频输入参数
     * @param userId 用户Id
     * @return 视频VO
     */
    ExplanationVideoDTO generateVideo(ProjectInput projectInput, String userId) throws JsonProcessingException;
}
