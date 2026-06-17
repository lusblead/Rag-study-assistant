package com.rag.studyassistant.course;

import com.rag.studyassistant.course.model.Course;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CourseMapper {

    @Insert("INSERT INTO courses (name, description, term) VALUES (#{name}, #{description}, #{term})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Course course);

    @Select("SELECT * FROM courses WHERE id = #{id}")
    Course selectById(Long id);

    @Select("<script>" +
        "SELECT * FROM courses" +
        "<if test='name != null and name != \"\"'>" +
        " WHERE name LIKE CONCAT('%',#{name},'%')" +
        "</if>" +
        " ORDER BY created_at DESC" +
        "</script>")
    List<Course> selectList(@Param("name") String name);

    @Update("UPDATE courses SET name=#{name}, description=#{description}, term=#{term} WHERE id=#{id}")
    int update(Course course);

    @Delete("DELETE FROM courses WHERE id = #{id}")
    int deleteById(Long id);
}
