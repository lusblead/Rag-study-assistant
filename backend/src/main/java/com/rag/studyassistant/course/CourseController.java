package com.rag.studyassistant.course;

import com.rag.studyassistant.common.Result;
import com.rag.studyassistant.course.model.Course;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @PostMapping
    public Result<Course> create(@RequestBody Course course) {
        Course created = courseService.create(course);
        return Result.ok(created);
    }

    @GetMapping
    public Result<List<Course>> list(@RequestParam(required = false) String name) {
        List<Course> courses = courseService.list(name);
        return Result.ok(courses);
    }

    @PutMapping("/{id}")
    public Result<Course> update(@PathVariable Long id, @RequestBody Course course) {
        Course updated = courseService.update(id, course);
        if (updated == null) {
            return Result.fail(404, "课程不存在");
        }
        return Result.ok(updated);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        courseService.delete(id);
        return Result.ok();
    }
}
