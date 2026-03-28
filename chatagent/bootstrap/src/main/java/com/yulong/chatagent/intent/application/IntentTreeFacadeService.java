package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.intent.model.request.CreateIntentNodeRequest;
import com.yulong.chatagent.intent.model.request.SetIntentNodeKnowledgeBasesRequest;
import com.yulong.chatagent.intent.model.request.UpdateIntentNodeRequest;
import com.yulong.chatagent.intent.model.response.CreateIntentNodeResponse;
import com.yulong.chatagent.intent.model.response.GetIntentTreeResponse;
import com.yulong.chatagent.intent.model.response.GetIntentVersionsResponse;
import com.yulong.chatagent.intent.model.response.PublishIntentTreeResponse;

/**
 * Administrator-facing intent-tree management for the single internal assistant.
 */
public interface IntentTreeFacadeService {

    GetIntentTreeResponse getIntentTree();

    CreateIntentNodeResponse createIntentNode(CreateIntentNodeRequest request);

    void updateIntentNode(String nodeId, UpdateIntentNodeRequest request);

    void deleteIntentNode(String nodeId);

    void setIntentNodeKnowledgeBases(String nodeId, SetIntentNodeKnowledgeBasesRequest request);

    PublishIntentTreeResponse publishIntentTreeSnapshot();

    GetIntentVersionsResponse getIntentVersions();

    void switchActiveIntentVersion(int version);
}
