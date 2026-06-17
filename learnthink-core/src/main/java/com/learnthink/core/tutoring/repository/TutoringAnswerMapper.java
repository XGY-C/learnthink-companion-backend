package com.learnthink.core.tutoring.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.TutoringAnswer;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TutoringAnswerMapper extends BaseMapper<TutoringAnswer> {}
