package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.model.IndexedChunkDocument;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import com.yulong.chatagent.support.dto.FileChunkDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionFileIndexedChunkAssemblerTest {

    private final SessionFileIndexedChunkAssembler assembler = new SessionFileIndexedChunkAssembler(new ObjectMapper());

    @Test
    void shouldAssembleSourceAwareChunkDocumentFromMetadata() {
        ChatSessionFileDTO sessionFile = ChatSessionFileDTO.builder()
                .id("file-1")
                .originalFilename("Operations Playbook.md")
                .build();
        FileChunkDTO chunk = FileChunkDTO.builder()
                .id("chunk-1")
                .chunkIndex(3)
                .content("Escalate to on-call within 15 minutes.")
                .metadata("""
                        {
                          "contextText": "Incident response procedure",
                          "retrievalText": "Incident response procedure\\n\\nEscalate to on-call within 15 minutes.",
                          "sectionPath": "Runbook / Escalation"
                        }
                        """)
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 3, 26, 9, 30))
                .build();

        List<IndexedChunkDocument> documents = assembler.assemble("session-1", sessionFile, List.of(chunk));

        assertThat(documents).hasSize(1);
        IndexedChunkDocument document = documents.get(0);
        assertThat(document.chunkId()).isEqualTo("chunk-1");
        assertThat(document.sourceType()).isEqualTo(RagSourceType.SESSION_FILE);
        assertThat(document.scopeId()).isEqualTo("session-1");
        assertThat(document.sourceId()).isEqualTo("file-1");
        assertThat(document.documentId()).isEqualTo("file-1");
        assertThat(document.documentName()).isEqualTo("Operations Playbook.md");
        assertThat(document.chunkIndex()).isEqualTo(3);
        assertThat(document.sectionPath()).isEqualTo("Runbook / Escalation");
        assertThat(document.contextText()).isEqualTo("Incident response procedure");
        assertThat(document.retrievalText()).contains("Escalate to on-call within 15 minutes.");
        assertThat(document.createdAtEpochMillis()).isPositive();
    }

    @Test
    void shouldFallbackToChunkContentAndTitleMetadata() {
        ChatSessionFileDTO sessionFile = ChatSessionFileDTO.builder()
                .id("file-2")
                .filename("faq.md")
                .build();
        FileChunkDTO chunk = FileChunkDTO.builder()
                .id("chunk-2")
                .content("VPN access requires MFA.")
                .metadata("""
                        {
                          "title": "Remote Access"
                        }
                        """)
                .enabled(null)
                .build();

        IndexedChunkDocument document = assembler.assemble("session-2", sessionFile, List.of(chunk)).get(0);

        assertThat(document.documentName()).isEqualTo("faq.md");
        assertThat(document.sectionPath()).isEqualTo("Remote Access");
        assertThat(document.contextText()).isNull();
        assertThat(document.retrievalText()).isEqualTo(String.join(
                System.lineSeparator() + System.lineSeparator(),
                "Section: Remote Access",
                "VPN access requires MFA."));
        assertThat(document.enabled()).isTrue();
    }
}
