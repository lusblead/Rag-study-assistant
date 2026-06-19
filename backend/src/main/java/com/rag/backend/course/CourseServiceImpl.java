package com.rag.backend.course;

import com.rag.backend.course.model.Course;
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
        return courseMapper.selectList(StringUtils.hasText(name) ? name : null);
    }

    @Override
    public Course update(Long id, Course course) {
        Course existing = courseMapper.selectById(id);
        if (existing == null) {
            return null;
        }
        course.setId(id);
        courseMapper.update(course);
        return courseMapper.selectById(id);
    }

    @Override
    public void delete(Long id) {
        Course existing = courseMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("课程不存在: " + id);
        }
        courseMapper.deleteById(id);
    }
}
