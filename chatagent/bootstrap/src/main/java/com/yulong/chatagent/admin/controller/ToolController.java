package com.yulong.chatagent.admin.controller;

import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.agent.application.ToolFacadeService;
import com.yulong.chatagent.model.common.ApiResponse;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for tool catalog lookup endpoints.
 */
@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ToolController {

    private final ToolFacadeService toolFacadeService;

    /**
     * Returns tools that users may optionally attach to an agent.
     *
     * @return optional tool list
     */
    @GetMapping("/tools")
    public ApiResponse<List<Tool>> getOptionalTools() {
        return ApiResponse.success(toolFacadeService.getOptionalTools());
    }
}
