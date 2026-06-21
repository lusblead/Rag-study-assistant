package com.rag.backend.agent.settings;

import com.rag.backend.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/model-settings")
public class AgentModelSettingsController {
    private final AgentModelSettingsService settingsService;

    public AgentModelSettingsController(AgentModelSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public Result<AgentModelSettingsResponse> get() {
        return Result.ok(settingsService.currentResponse());
    }

    @PutMapping
    public Result<AgentModelSettingsResponse> update(@RequestBody AgentModelSettingsRequest request) {
        return Result.ok(settingsService.update(request));
    }

    @PostMapping("/test")
    public Result<AgentModelSettingsTestResponse> test(@RequestBody AgentModelSettingsTestRequest request) {
        return Result.ok(settingsService.test(request));
    }
}
