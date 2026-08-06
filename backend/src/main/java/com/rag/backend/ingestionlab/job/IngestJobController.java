package com.rag.backend.ingestionlab.job;

import com.rag.backend.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingestion/jobs")
public class IngestJobController {
    private final IngestJobQueryService queryService;
    public IngestJobController(IngestJobQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{jobId}")
    public Result<IngestJobQueryResponse> getJob(@PathVariable String jobId) {
        IngestJobQueryResponse response = queryService.selectById(jobId);
        return Result.ok(response);
    }
}
