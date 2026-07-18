package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.ResourceFolder;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResourceFolderMapper extends BaseMapper<ResourceFolder> {
}
