package com.rag.backend.rerank;

import com.rag.backend.common.Result;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rerank-settings")
public class RerankSettingsController {
    private final RerankSettingsService service;

    public RerankSettingsController(RerankSettingsService service) { this.service = service; }

    @GetMapping
    public Result<RerankSettingsResponse> get() { return Result.ok(service.response()); }

    @PutMapping
    public Result<RerankSettingsResponse> update(@RequestBody RerankSettingsRequest request) {
        return Result.ok(service.update(request));
    }

    @PostMapping("/test")
    public Result<RerankSettingsResponse> test(@RequestBody RerankSettingsRequest request) {
        return Result.ok(service.test(request));
    }
}
