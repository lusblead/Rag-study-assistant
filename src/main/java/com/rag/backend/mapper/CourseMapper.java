package com.rag.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.backend.entity.Course;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CourseMapper extends BaseMapper<Course> {
}
