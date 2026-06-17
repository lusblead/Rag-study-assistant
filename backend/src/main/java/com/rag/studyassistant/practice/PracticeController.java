package com.rag.studyassistant.practice;

import com.rag.studyassistant.common.Result;
import com.rag.studyassistant.practice.model.PracticeRecord;
import com.rag.studyassistant.practice.model.SubmitAnswerRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class PracticeController {

    private final PracticeService practiceService;

    public PracticeController(PracticeService practiceService) {
        this.practiceService = practiceService;
    }

    /**
     * 提交答案
     * POST /api/practice/submit
     */
    @PostMapping("/api/practice/submit")
    public Result<PracticeRecord> submit(@RequestBody SubmitAnswerRequest request) {
        PracticeRecord record = practiceService.submit(
                request.getCourseId(), request.getQuestionId(), request.getUserAnswer());
        return Result.ok(record);
    }

    /**
     * 查询练习记录
     * GET /api/courses/{courseId}/practice/records
     */
    @GetMapping("/api/courses/{courseId}/practice/records")
    public Result<List<PracticeRecord>> listRecords(@PathVariable Long courseId) {
        List<PracticeRecord> records = practiceService.listRecords(courseId);
        return Result.ok(records);
    }

    /**
     * 查询错题
     * GET /api/courses/{courseId}/practice/wrong-questions
     */
    @GetMapping("/api/courses/{courseId}/practice/wrong-questions")
    public Result<List<PracticeRecord>> listWrongQuestions(@PathVariable Long courseId) {
        List<PracticeRecord> records = practiceService.listWrongQuestions(courseId);
        return Result.ok(records);
    }
}
