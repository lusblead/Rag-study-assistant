package com.example.ragstudyassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ragstudyassistant.entity.Course;
import com.example.ragstudyassistant.mapper.CourseMapper;
import com.example.ragstudyassistant.service.CourseService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class CourseServiceImpl implements CourseService {

    private final CourseMapper courseMapper;

    public CourseServiceImpl(CourseMapper courseMapper) {
        this.courseMapper = courseMapper;
    }

    @Override
    public Course create(Course course) {
        courseMapper.insert(course);
        return course;
    }

    @Override
    public List<Course> list(String name) {
        LambdaQueryWrapper<Course> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(name)) {
            wrapper.like(Course::getName, name);
        }
        wrapper.orderByDesc(Course::getCreatedAt);
        return courseMapper.selectList(wrapper);
    }

    @Override
    public Course update(Long id, Course course) {
        Course existing = courseMapper.selectById(id);
        if (existing == null) {
            return null;
        }
        course.setId(id);
        courseMapper.updateById(course);
        return courseMapper.selectById(id);
    }

    @Override
    public void delete(Long id) {
        courseMapper.deleteById(id);
    }
}
