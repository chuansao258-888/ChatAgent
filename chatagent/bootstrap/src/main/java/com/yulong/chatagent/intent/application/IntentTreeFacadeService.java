package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.intent.model.request.SetIntentNodeKnowledgeBasesRequest;
import com.yulong.chatagent.intent.model.request.UpsertIntentNodeRequest;
import com.yulong.chatagent.intent.model.response.CreateIntentNodeResponse;
import com.yulong.chatagent.intent.model.response.GetIntentTreeResponse;
import com.yulong.chatagent.intent.model.vo.IntentVersionVO;

import java.util.List;

/**
 * Administrator-facing intent-tree management for the single internal assistant.
 */
public interface IntentTreeFacadeService {

    GetIntentTreeResponse getIntentTree();

    CreateIntentNodeResponse createIntentNode(UpsertIntentNodeRequest request);

    void updateIntentNode(String nodeId, UpsertIntentNodeRequest request);

    void deleteIntentNode(String nodeId);

    void setIntentNodeKnowledgeBases(String nodeId, SetIntentNodeKnowledgeBasesRequest request);

    /**
     * Publishes the current draft tree as a new immutable snapshot and
     * returns the newly assigned version number.
     */
    Integer publishIntentTreeSnapshot();

    List<IntentVersionVO> getIntentVersions();

    void switchActiveIntentVersion(int version);

    /**
     * Removes all draft (version 0) nodes for the system assistant.
     *
     * <p>Used by admin orchestration (e.g. template initialization) to
     * reset the draft tree without a direct dependency on the intent
     * persistence port.</p>
     */
    void clearDraftTree();
}
