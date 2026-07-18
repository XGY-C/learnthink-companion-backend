package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.RecommendationFeedback;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RecommendationFeedbackMapper extends BaseMapper<RecommendationFeedback> {
}
