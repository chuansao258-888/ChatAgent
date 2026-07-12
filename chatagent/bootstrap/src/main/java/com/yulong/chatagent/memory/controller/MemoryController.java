package com.yulong.chatagent.memory.controller;

import com.yulong.chatagent.memory.application.MemoryApplicationService;
import com.yulong.chatagent.model.common.ApiResponse;
import com.yulong.chatagent.context.UserContext;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {
    public record CreateMemoryRequest(String type, String content) {}
    public record UpdateMemoryRequest(String type, String content, LocalDateTime expectedUpdatedAt) {}
    private final MemoryApplicationService service;
    public MemoryController(MemoryApplicationService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<MemoryApplicationService.WritableMemory>> list() {
        return ApiResponse.success(service.list(userId()));
    }
    @PostMapping
    public ApiResponse<MemoryApplicationService.WritableMemory> create(@RequestBody CreateMemoryRequest request) {
        return ApiResponse.success(service.create(userId(), request.type(), request.content()));
    }
    @PatchMapping("/{memoryId}")
    public ApiResponse<MemoryApplicationService.CorrectResult> update(@PathVariable String memoryId,
                                                                       @RequestBody UpdateMemoryRequest request) {
        return ApiResponse.success(service.correct(userId(), memoryId, request.expectedUpdatedAt(), request.type(), request.content()));
    }
    @DeleteMapping("/{memoryId}")
    public ApiResponse<Boolean> delete(@PathVariable String memoryId) {
        return ApiResponse.success(service.archive(userId(), memoryId));
    }
    private String userId() { return UserContext.requireUser().getUserId(); }
}
