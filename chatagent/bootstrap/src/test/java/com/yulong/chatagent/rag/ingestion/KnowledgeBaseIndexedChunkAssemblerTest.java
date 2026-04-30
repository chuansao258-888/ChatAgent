package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.model.IndexedChunkDocument;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseIndexedChunkAssemblerTest {

    private final KnowledgeBaseIndexedChunkAssembler assembler = new KnowledgeBaseIndexedChunkAssembler(new ObjectMapper());

    @Test
    void shouldAssembleKnowledgeBaseChunkIntoSourceAwareDocument() {
        KnowledgeDocumentDTO knowledgeDocument = KnowledgeDocumentDTO.builder()
                .id("doc-1")
                .originalFilename("HR Handbook.md")
                .build();
        KnowledgeChunkDTO chunk = KnowledgeChunkDTO.builder()
                .id("chunk-1")
                .chunkIndex(2)
                .content("Employees can carry over up to 5 leave days.")
                .metadata("""
                        {
                          "contextText": "Annual leave policy",
                          "retrievalText": "Annual leave policy\\n\\nEmployees can carry over up to 5 leave days.",
                          "sectionPath": "Benefits / Leave"
                        }
                        """)
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 3, 27, 10, 15))
                .build();

        List<IndexedChunkDocument> documents = assembler.assemble("kb-1", knowledgeDocument, List.of(chunk));

        assertThat(documents).hasSize(1);
        IndexedChunkDocument document = documents.get(0);
        assertThat(document.chunkId()).isEqualTo("chunk-1");
        assertThat(document.sourceType()).isEqualTo(RagSourceType.KNOWLEDGE_BASE);
        assertThat(document.scopeId()).isEqualTo("kb-1");
        assertThat(document.sourceId()).isEqualTo("kb-1");
        assertThat(document.documentId()).isEqualTo("doc-1");
        assertThat(document.documentName()).isEqualTo("HR Handbook.md");
        assertThat(document.chunkIndex()).isEqualTo(2);
        assertThat(document.sectionPath()).isEqualTo("Benefits / Leave");
        assertThat(document.contextText()).isEqualTo("Annual leave policy");
        assertThat(document.retrievalText()).contains("carry over up to 5 leave days");
        assertThat(document.createdAtEpochMillis()).isPositive();
    }

    @Test
    void shouldFallbackToContentAndHeadingMetadata() {
        KnowledgeDocumentDTO knowledgeDocument = KnowledgeDocumentDTO.builder()
                .id("doc-2")
                .filename("security.md")
                .build();
        KnowledgeChunkDTO chunk = KnowledgeChunkDTO.builder()
                .id("chunk-2")
                .content("VPN access requires MFA for all employees.")
                .metadata("""
                        {
                          "headingPath": "Security / Remote Access"
                        }
                        """)
                .enabled(null)
                .build();

        IndexedChunkDocument document = assembler.assemble("kb-2", knowledgeDocument, List.of(chunk)).get(0);

        assertThat(document.documentName()).isEqualTo("security.md");
        assertThat(document.sectionPath()).isEqualTo("Security / Remote Access");
        assertThat(document.contextText()).isNull();
        assertThat(document.retrievalText()).isEqualTo(String.join(
                System.lineSeparator() + System.lineSeparator(),
                "Section: Security / Remote Access",
                "VPN access requires MFA for all employees."));
        assertThat(document.enabled()).isTrue();
    }
}
