package com.learnthink.core.tutoring.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.TutoringSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TutoringSessionMapper extends BaseMapper<TutoringSession> {}
