package com.rag.backend.question;

import com.rag.backend.common.Result;
import com.rag.backend.question.model.Question;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    /**
     * B9: 保存单个题目
     * POST /api/questions
     */
    @PostMapping
    public Result<Question> save(@RequestBody Question question) {
        Question saved = questionService.save(question);
        return Result.ok(saved);
    }

    /**
     * B10: 题目列表
     * GET /api/questions?courseId=&type=&difficulty=
     */
    @GetMapping
    public Result<List<Question>> list(@RequestParam("courseId") Long courseId,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(required = false) String difficulty,
                                       @RequestParam(required = false) String subject) {
        List<Question> questions = questionService.listByCourse(courseId, type, difficulty, subject);
        return Result.ok(questions);
    }

    @PutMapping("/{id}")
    public Result<Question> update(@PathVariable Long id, @RequestBody Question question) {
        return Result.ok(questionService.update(id, question));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        questionService.delete(id);
        return Result.ok(null);
    }
}
