package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.UpsertMcpServerRequest;
import com.yulong.chatagent.admin.model.response.DeleteMcpServerResponse;
import com.yulong.chatagent.admin.model.response.GetMcpServerResponse;
import com.yulong.chatagent.admin.model.response.SyncMcpToolCatalogResponse;
import com.yulong.chatagent.admin.model.response.TestMcpServerResponse;
import com.yulong.chatagent.admin.model.vo.McpServerVO;

import java.util.List;

/**
 * Administrative MCP server management use cases.
 */
public interface McpServerAdminFacadeService {

    List<McpServerVO> getServers();

    GetMcpServerResponse getServer(String serverId);

    String createServer(UpsertMcpServerRequest request);

    void updateServer(String serverId, UpsertMcpServerRequest request);

    DeleteMcpServerResponse deleteServer(String serverId, boolean force);

    TestMcpServerResponse testServer(String serverId);

    SyncMcpToolCatalogResponse syncServer(String serverId);
}
