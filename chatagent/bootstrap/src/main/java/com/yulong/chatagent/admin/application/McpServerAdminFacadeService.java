package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.CreateMcpServerRequest;
import com.yulong.chatagent.admin.model.request.UpdateMcpServerRequest;
import com.yulong.chatagent.admin.model.response.DeleteMcpServerResponse;
import com.yulong.chatagent.admin.model.response.GetMcpServerResponse;
import com.yulong.chatagent.admin.model.response.ListMcpServersResponse;
import com.yulong.chatagent.admin.model.response.SyncMcpToolCatalogResponse;
import com.yulong.chatagent.admin.model.response.TestMcpServerResponse;

/**
 * Administrative MCP server management use cases.
 */
public interface McpServerAdminFacadeService {

    ListMcpServersResponse getServers();

    GetMcpServerResponse getServer(String serverId);

    String createServer(CreateMcpServerRequest request);

    void updateServer(String serverId, UpdateMcpServerRequest request);

    DeleteMcpServerResponse deleteServer(String serverId, boolean force);

    TestMcpServerResponse testServer(String serverId);

    SyncMcpToolCatalogResponse syncServer(String serverId);
}
