package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.agent.runtime.CurrentChatSessionHolder;
import com.yulong.chatagent.rag.service.RagService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fixed tool for similarity search across files attached to the current chat session.
 */
@Component
public class SessionFileTools implements Tool {

    private final RagService ragService;

    public SessionFileTools(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String getName() {
        return "SessionFileSearchTool";
    }

    @Override
    public String getDescription() {
        return "Perform semantic retrieval against the files attached to the current chat session.";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "SessionFileSearchTool",
            description = "Run similarity search against the files attached to the current chat session. Argument: query."
    )
    public String knowledgeQuery(String query) {
        String chatSessionId = CurrentChatSessionHolder.require();
        List<String> results = ragService.similaritySearchBySession(chatSessionId, query);
        return String.join("\n", results);
    }
}
