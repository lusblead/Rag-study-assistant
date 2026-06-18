package com.rag.backend.document;

import com.rag.backend.document.model.CourseDocument;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DocumentMapper {

    @Insert("INSERT INTO documents (course_id, filename, file_type, file_path, parse_status, chunk_count) " +
            "VALUES (#{courseId}, #{filename}, #{fileType}, #{filePath}, #{parseStatus}, #{chunkCount})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CourseDocument doc);

    @Select("SELECT * FROM documents WHERE id = #{id}")
    CourseDocument selectById(Long id);

    @Select("SELECT * FROM documents WHERE course_id = #{courseId} ORDER BY created_at DESC")
    List<CourseDocument> selectListByCourseId(Long courseId);

    @Update("UPDATE documents SET parse_status=#{parseStatus}, chunk_count=#{chunkCount} WHERE id=#{id}")
    int updateParseStatus(CourseDocument doc);

    @Delete("DELETE FROM documents WHERE id = #{id}")
    int deleteById(Long id);
}
