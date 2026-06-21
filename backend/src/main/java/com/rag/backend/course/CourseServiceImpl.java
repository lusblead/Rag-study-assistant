package com.rag.backend.course;

import com.rag.backend.agent.ingest.AgentDocumentCleanupService;
import com.rag.backend.agent.history.ChatHistoryService;
import com.rag.backend.common.BizException;
import com.rag.backend.course.model.Course;
import com.rag.backend.document.DocumentMapper;
import com.rag.backend.document.model.CourseDocument;
import com.rag.backend.practice.PracticeMapper;
import com.rag.backend.question.QuestionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class CourseServiceImpl implements CourseService {

    private final CourseMapper courseMapper;
    private final DocumentMapper documentMapper;
    private final QuestionMapper questionMapper;
    private final PracticeMapper practiceMapper;
    private final AgentDocumentCleanupService cleanupService;
    private final ChatHistoryService chatHistoryService;

    public CourseServiceImpl(CourseMapper courseMapper,
                             DocumentMapper documentMapper,
                             QuestionMapper questionMapper,
                             PracticeMapper practiceMapper,
                             AgentDocumentCleanupService cleanupService,
                             ChatHistoryService chatHistoryService) {
        this.courseMapper = courseMapper;
        this.documentMapper = documentMapper;
        this.questionMapper = questionMapper;
        this.practiceMapper = practiceMapper;
        this.cleanupService = cleanupService;
        this.chatHistoryService = chatHistoryService;
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
    @Transactional
    public void delete(Long id) {
        Course existing = courseMapper.selectById(id);
        if (existing == null) {
            throw new BizException(404, "课程不存在: " + id);
        }

        List<CourseDocument> documents = documentMapper.selectListByCourseId(id);
        for (CourseDocument document : documents) {
            cleanupService.cleanupDocument(document.getId());
            deleteUploadedFile(document);
        }

        cleanupService.cleanupCourse(id);
        chatHistoryService.deleteByCourseId(id);
        practiceMapper.deleteByCourseId(id);
        questionMapper.deleteByCourseId(id);
        documentMapper.deleteByCourseId(id);
        courseMapper.deleteById(id);
    }

    private void deleteUploadedFile(CourseDocument document) {
        if (document.getFilePath() == null || document.getFilePath().isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(document.getFilePath()));
        } catch (IOException e) {
            throw new BizException(500, "删除课程文件失败: " + document.getFilePath());
        }
    }
}
