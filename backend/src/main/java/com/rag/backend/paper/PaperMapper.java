package com.rag.backend.paper;

import com.rag.backend.paper.model.Paper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface PaperMapper {
    @Insert("INSERT INTO papers(course_id,subject,title,paper_type,grade_level,difficulty,duration_minutes,total_score,template_code,requirements,warnings) VALUES(#{courseId},#{subject},#{title},#{paperType},#{gradeLevel},#{difficulty},#{durationMinutes},#{totalScore},#{templateCode},#{requirements},#{warnings})")
    @Options(useGeneratedKeys=true,keyProperty="id") int insert(Paper paper);
    @Select("SELECT * FROM papers WHERE id=#{id}") Paper selectById(Long id);
    @Select("<script>SELECT * FROM papers WHERE course_id=#{courseId}<if test='subject != null and subject != \"\"'> AND subject=#{subject}</if> ORDER BY created_at DESC</script>")
    List<Paper> selectList(@Param("courseId") Long courseId,@Param("subject") String subject);
    @Update("UPDATE papers SET subject=#{subject},title=#{title},paper_type=#{paperType},grade_level=#{gradeLevel},difficulty=#{difficulty},duration_minutes=#{durationMinutes},total_score=#{totalScore},template_code=#{templateCode},requirements=#{requirements},warnings=#{warnings},updated_at=CURRENT_TIMESTAMP WHERE id=#{id}") int update(Paper paper);
    @Delete("DELETE FROM papers WHERE id=#{id}") int deleteById(Long id);
    @Delete("DELETE FROM papers WHERE course_id=#{courseId}") int deleteByCourseId(Long courseId);
}
