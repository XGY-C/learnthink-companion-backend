package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.ProfileVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProfileVersionMapper extends BaseMapper<ProfileVersion> {
}
