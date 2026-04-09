package com.yulong.chatagent.admin.controller;

import com.yulong.chatagent.access.RequireRole;
import com.yulong.chatagent.access.UserRole;
import com.yulong.chatagent.admin.application.McpServerAdminFacadeService;
import com.yulong.chatagent.admin.model.request.CreateMcpServerRequest;
import com.yulong.chatagent.admin.model.request.UpdateMcpServerRequest;
import com.yulong.chatagent.admin.model.response.DeleteMcpServerResponse;
import com.yulong.chatagent.admin.model.response.GetMcpServerResponse;
import com.yulong.chatagent.admin.model.response.ListMcpServersResponse;
import com.yulong.chatagent.admin.model.response.SyncMcpToolCatalogResponse;
import com.yulong.chatagent.admin.model.response.TestMcpServerResponse;
import com.yulong.chatagent.model.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator MCP server management endpoints.
 */
@RestController
@RequestMapping("/api/admin/mcp-servers")
@RequireRole(UserRole.ADMIN)
@RequiredArgsConstructor
public class McpServerAdminController {

    private final McpServerAdminFacadeService mcpServerAdminFacadeService;

    @GetMapping
    public ApiResponse<ListMcpServersResponse> getServers() {
        return ApiResponse.success(mcpServerAdminFacadeService.getServers());
    }

    @GetMapping("/{serverId}")
    public ApiResponse<GetMcpServerResponse> getServer(@PathVariable String serverId) {
        return ApiResponse.success(mcpServerAdminFacadeService.getServer(serverId));
    }

    @PostMapping
    public ApiResponse<String> createServer(@RequestBody CreateMcpServerRequest request) {
        return ApiResponse.success(mcpServerAdminFacadeService.createServer(request));
    }

    @PatchMapping("/{serverId}")
    public ApiResponse<Void> updateServer(@PathVariable String serverId,
                                          @RequestBody UpdateMcpServerRequest request) {
        mcpServerAdminFacadeService.updateServer(serverId, request);
        return ApiResponse.success();
    }

    @PostMapping("/{serverId}/test")
    public ApiResponse<TestMcpServerResponse> testServer(@PathVariable String serverId) {
        return ApiResponse.success(mcpServerAdminFacadeService.testServer(serverId));
    }

    @PostMapping("/{serverId}/sync")
    public ApiResponse<SyncMcpToolCatalogResponse> syncServer(@PathVariable String serverId) {
        return ApiResponse.success(mcpServerAdminFacadeService.syncServer(serverId));
    }

    @DeleteMapping("/{serverId}")
    public ApiResponse<DeleteMcpServerResponse> deleteServer(@PathVariable String serverId,
                                                             @RequestParam(defaultValue = "false") boolean force) {
        return ApiResponse.success(mcpServerAdminFacadeService.deleteServer(serverId, force));
    }
}
