package com.rag.backend.question;

import com.rag.backend.question.model.QuestionBatch;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface QuestionBatchMapper {
    @Insert("""
            INSERT INTO question_batches
            (course_id, title, mode, requirement, question_count, question_type, difficulty, reference_real_questions, style_summary)
            VALUES
            (#{courseId}, #{title}, #{mode}, #{requirement}, #{questionCount}, #{questionType}, #{difficulty}, #{referenceRealQuestions}, #{styleSummary})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(QuestionBatch batch);

    @Select("SELECT * FROM question_batches WHERE id = #{id}")
    QuestionBatch selectById(Long id);

    @Select("SELECT * FROM question_batches WHERE course_id = #{courseId} ORDER BY created_at DESC, id DESC")
    List<QuestionBatch> selectByCourseId(Long courseId);

    @Insert("INSERT INTO question_batch_documents (batch_id, document_id) VALUES (#{batchId}, #{documentId})")
    int insertDocument(@Param("batchId") Long batchId, @Param("documentId") Long documentId);

    @Insert("INSERT INTO question_batch_chunks (batch_id, chunk_id) VALUES (#{batchId}, #{chunkId})")
    int insertChunk(@Param("batchId") Long batchId, @Param("chunkId") Long chunkId);

    @Select("SELECT document_id FROM question_batch_documents WHERE batch_id = #{batchId} ORDER BY document_id")
    List<Long> selectDocumentIds(Long batchId);

    @Select("SELECT chunk_id FROM question_batch_chunks WHERE batch_id = #{batchId} ORDER BY chunk_id")
    List<Long> selectChunkIds(Long batchId);

    @Select("""
            SELECT DISTINCT qbc.chunk_id
            FROM question_batch_chunks qbc
            JOIN question_batches qb ON qb.id = qbc.batch_id
            WHERE qb.course_id = #{courseId}
            """)
    List<Long> selectUsedChunkIds(Long courseId);

    @Select("""
            SELECT style_summary FROM question_batches
            WHERE course_id = #{courseId}
              AND reference_real_questions = TRUE
              AND style_summary IS NOT NULL
              AND style_summary <> ''
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """)
    String selectLatestStyleSummary(Long courseId);

    @Delete("DELETE FROM question_batch_documents WHERE batch_id = #{batchId}")
    int deleteDocuments(Long batchId);

    @Delete("DELETE FROM question_batch_chunks WHERE batch_id = #{batchId}")
    int deleteChunks(Long batchId);

    @Delete("DELETE FROM question_batches WHERE id = #{id}")
    int deleteById(Long id);

    @Delete("DELETE FROM question_batch_documents WHERE batch_id IN (SELECT id FROM question_batches WHERE course_id = #{courseId})")
    int deleteDocumentsByCourseId(Long courseId);

    @Delete("DELETE FROM question_batch_chunks WHERE batch_id IN (SELECT id FROM question_batches WHERE course_id = #{courseId})")
    int deleteChunksByCourseId(Long courseId);

    @Delete("DELETE FROM question_batches WHERE course_id = #{courseId}")
    int deleteByCourseId(Long courseId);
}
