package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.rag.service.RagService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fixed tool for knowledge-base similarity search.
 */
@Component
public class KnowledgeTools implements Tool {

    private final RagService ragService;

    public KnowledgeTools(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String getName() {
        return "KnowledgeTool";
    }

    @Override
    public String getDescription() {
        return "Perform semantic retrieval against a knowledge base.";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeTool",
            description = "Run similarity search against a knowledge base. Arguments: kbsId and query."
    )
    public String knowledgeQuery(String kbsId, String query) {
        List<String> results = ragService.similaritySearch(kbsId, query);
        return String.join("\n", results);
    }
}
