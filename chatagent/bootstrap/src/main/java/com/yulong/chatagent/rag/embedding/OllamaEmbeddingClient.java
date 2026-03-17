package com.yulong.chatagent.rag.embedding;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Component
public class OllamaEmbeddingClient {

    private final WebClient webClient;
    private final String embeddingModel;

    public OllamaEmbeddingClient(WebClient.Builder builder,
                                 @Value("${rag.embedding.base-url:http://localhost:11434}") String baseUrl,
                                 @Value("${rag.embedding.model:bge-m3}") String embeddingModel) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.embeddingModel = embeddingModel;
    }

    public float[] embed(String text) {
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", embeddingModel,
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();
        Assert.notNull(resp, "Embedding response cannot be null");
        return resp.getEmbedding();
    }

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }
}
