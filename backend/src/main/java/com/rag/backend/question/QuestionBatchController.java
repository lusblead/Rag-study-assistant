package com.rag.backend.question;

import com.rag.backend.common.Result;
import com.rag.backend.question.model.QuestionBatchDetail;
import com.rag.backend.question.model.QuestionGenerationRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/question-batches")
public class QuestionBatchController {
    private final QuestionBatchGenerationService service;

    public QuestionBatchController(QuestionBatchGenerationService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    public Result<QuestionBatchDetail> generate(@RequestBody QuestionGenerationRequest request) {
        return Result.ok(service.generate(request));
    }

    @GetMapping
    public Result<List<QuestionBatchDetail>> list(@RequestParam Long courseId) {
        return Result.ok(service.list(courseId));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteBatch(id);
        return Result.ok(null);
    }
}
