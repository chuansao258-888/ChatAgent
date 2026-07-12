package com.yulong.chatagent.memory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.memory.port.MemoryItemRepository;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.support.dto.MemoryItemDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MemoryApplicationServiceTest {
    private final MemoryItemRepository repository = mock(MemoryItemRepository.class);
    private final OllamaEmbeddingClient embedding = mock(OllamaEmbeddingClient.class);
    private final UserMemoryIndexService index = mock(UserMemoryIndexService.class);
    private final MemoryApplicationService service = new MemoryApplicationService(repository, embedding, index, new ObjectMapper());

    @Test
    void ambiguousInspectionNeverReturnsWritableIds() {
        when(repository.findByUserIdAndStatus("u", "active")).thenReturn(List.of(item("1", "likes tea"), item("2", "likes tea shops")));
        var result = service.inspect("u", "tea");
        assertThat(result.status()).isEqualTo(MemoryApplicationService.InspectStatus.AMBIGUOUS);
        assertThat(result.memory()).isNull();
        assertThat(result.candidates()).hasSize(2);
    }

    @Test
    void staleCorrectionConflictsButSameDesiredValueIsIdempotent() {
        MemoryItemDTO current = item("1", "old");
        when(repository.findOwnedById("u", "1")).thenReturn(current);
        assertThat(service.correct("u", "1", current.getUpdatedAt().minusSeconds(1), "fact", "new").status())
                .isEqualTo(MemoryApplicationService.CorrectStatus.CONFLICT);
        assertThat(service.correct("u", "1", current.getUpdatedAt().minusSeconds(1), "fact", "old").status())
                .isEqualTo(MemoryApplicationService.CorrectStatus.ALREADY_APPLIED);
        verify(repository, never()).correct(any(), any(), any(), any(), any(), any());
    }

    @Test
    void conversationCorrectionRejectsEvidenceNotPresentInRawInput() {
        var result = service.correctFromConversation("u", "please change it to green", "invented", "1",
                LocalDateTime.now(), "fact", "green");
        assertThat(result.status()).isEqualTo(MemoryApplicationService.CorrectStatus.INVALID_EVIDENCE);
        verifyNoInteractions(repository);
    }

    private MemoryItemDTO item(String id, String content) {
        return MemoryItemDTO.builder().id(id).userId("u").type("fact").content(content).status("active")
                .updatedAt(LocalDateTime.of(2026, 1, 1, 1, 1)).build();
    }
}
