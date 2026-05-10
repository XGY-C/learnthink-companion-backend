package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.ProfileChat;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProfileChatMapper extends BaseMapper<ProfileChat> {
}
