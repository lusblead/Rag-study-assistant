package com.rag.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.backend.entity.Question;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {
}
