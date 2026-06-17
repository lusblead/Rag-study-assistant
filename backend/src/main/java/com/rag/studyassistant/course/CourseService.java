package com.rag.studyassistant.course;

import com.rag.studyassistant.course.model.Course;

import java.util.List;

public interface CourseService {

    Course create(Course course);

    List<Course> list(String name);

    Course update(Long id, Course course);

    void delete(Long id);
}
