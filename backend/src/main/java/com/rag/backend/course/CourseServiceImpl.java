package com.rag.backend.course;

import com.rag.backend.agent.ingest.AgentDocumentCleanupService;
import com.rag.backend.agent.history.ChatHistoryService;
import com.rag.backend.common.BizException;
import com.rag.backend.course.model.Course;
import com.rag.backend.document.DocumentMapper;
import com.rag.backend.document.model.CourseDocument;
import com.rag.backend.practice.PracticeMapper;
import com.rag.backend.paper.PaperMapper;
import com.rag.backend.paper.PaperQuestionMapper;
import com.rag.backend.question.QuestionMapper;
import com.rag.backend.question.QuestionBatchMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class CourseServiceImpl implements CourseService {

    private final CourseMapper courseMapper;
    private final DocumentMapper documentMapper;
    private final QuestionMapper questionMapper;
    private final QuestionBatchMapper questionBatchMapper;
    private final PracticeMapper practiceMapper;
    private final AgentDocumentCleanupService cleanupService;
    private final ChatHistoryService chatHistoryService;
    private final PaperMapper paperMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private com.rag.backend.ingestionlab.delete.DeleteRequestService deleteRequests;

    @org.springframework.beans.factory.annotation.Autowired
    public void setDeleteRequests(com.rag.backend.ingestionlab.delete.DeleteRequestService deleteRequests) {
        this.deleteRequests = deleteRequests;
    }

    public CourseServiceImpl(CourseMapper courseMapper,
                             DocumentMapper documentMapper,
                             QuestionMapper questionMapper,
                             QuestionBatchMapper questionBatchMapper,
                             PracticeMapper practiceMapper,
                             AgentDocumentCleanupService cleanupService,
                             ChatHistoryService chatHistoryService,
                             PaperMapper paperMapper,
                             PaperQuestionMapper paperQuestionMapper) {
        this.courseMapper = courseMapper;
        this.documentMapper = documentMapper;
        this.questionMapper = questionMapper;
        this.questionBatchMapper = questionBatchMapper;
        this.practiceMapper = practiceMapper;
        this.cleanupService = cleanupService;
        this.chatHistoryService = chatHistoryService;
        this.paperMapper = paperMapper;
        this.paperQuestionMapper = paperQuestionMapper;
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
    @Transactional(noRollbackFor = CourseDeletionPendingException.class)
    public void delete(Long id) {
        Course existing = courseMapper.selectById(id);
        if (existing == null) {
            throw new BizException(404, "课程不存在: " + id);
        }

        List<CourseDocument> documents = documentMapper.selectListByCourseId(id);
        if (!documents.isEmpty()) {
            for (CourseDocument document : documents) deleteRequests.request(document.getId());
            throw new CourseDeletionPendingException();
        }
        cleanupService.cleanupCourse(id);
        chatHistoryService.deleteByCourseId(id);
        practiceMapper.deleteByCourseId(id);
        paperQuestionMapper.deleteByCourseId(id);
        paperMapper.deleteByCourseId(id);
        questionMapper.deleteByCourseId(id);
        questionBatchMapper.deleteDocumentsByCourseId(id);
        questionBatchMapper.deleteChunksByCourseId(id);
        questionBatchMapper.deleteByCourseId(id);
        documentMapper.deleteByCourseId(id);
        courseMapper.deleteById(id);
    }

    public static class CourseDeletionPendingException extends BizException {
        public CourseDeletionPendingException() {
            super(409, "课程资料已进入安全删除队列，请待文档清理完成后再次删除课程。");
        }
    }
}
