package com.rag.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.backend.entity.Document;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
