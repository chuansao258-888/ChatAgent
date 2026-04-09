package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.agent.runtime.CurrentChatSessionHolder;
import com.yulong.chatagent.agent.runtime.CurrentIntentResolutionHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnKnowledgeHitHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnHolder;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.rag.service.FormattedRetrievalPrompt;
import com.yulong.chatagent.rag.model.RetrievalHit;
import com.yulong.chatagent.rag.service.RagService;
import com.yulong.chatagent.rag.service.RetrievalHitFormatter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fixed tool for similarity search across files attached to the current chat session.
 */
@Component
public class SessionFileTools implements Tool {

    private final RagService ragService;
    private final RetrievalHitFormatter retrievalHitFormatter;
    private final CurrentTurnCitationHolder currentTurnCitationHolder;

    public SessionFileTools(RagService ragService,
                            RetrievalHitFormatter retrievalHitFormatter,
                            CurrentTurnCitationHolder currentTurnCitationHolder) {
        this.ragService = ragService;
        this.retrievalHitFormatter = retrievalHitFormatter;
        this.currentTurnCitationHolder = currentTurnCitationHolder;
    }

    @Override
    public String getName() {
        return "SessionFileSearchTool";
    }

    @Override
    public String getDescription() {
        return "Perform semantic retrieval against the files attached to the current chat session and the internal assistant knowledge bases bound to this conversation.";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "SessionFileSearchTool",
            description = "Run similarity search against the files attached to the current chat session and the bound internal assistant knowledge bases. Argument: query."
    )
    public String knowledgeQuery(String query) {
        String chatSessionId = CurrentChatSessionHolder.require();
        String turnId = CurrentTurnHolder.require();
        IntentResolution intentResolution = CurrentIntentResolutionHolder.get();
        List<RetrievalHit> results = ragService.similaritySearchBySession(chatSessionId, query, intentResolution);
        CurrentTurnKnowledgeHitHolder.recordRetrievalResult(!results.isEmpty());
        FormattedRetrievalPrompt formatted = retrievalHitFormatter.formatWithCitations(results);
        currentTurnCitationHolder.put(chatSessionId, turnId, formatted.citations());
        return formatted.promptText();
    }
}
