package com.yulong.chatagent.support.persistence.adapter.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.support.dto.MemoryItemDTO;
import com.yulong.chatagent.support.persistence.mapper.MemoryItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyBatisMemoryItemRepositoryTest {

    @Mock
    private MemoryItemMapper memoryItemMapper;

    private MyBatisMemoryItemRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MyBatisMemoryItemRepository(memoryItemMapper, new ObjectMapper());
    }

    @Test
    void shouldSetDefaultsAndUpsertNewItem() {
        MemoryItemDTO item = MemoryItemDTO.builder()
                .userId("user-1")
                .type("preference")
                .content("User prefers dark mode")
                .contentHash("abc123")
                .tags(List.of("ui", "theme"))
                .source(Map.of("session_id", "session-1"))
                .build();

        MemoryItemDTO persisted = MemoryItemDTO.builder()
                .id("id-1")
                .userId("user-1")
                .type("preference")
                .content("User prefers dark mode")
                .contentHash("abc123")
                .tagsJson("[\"ui\",\"theme\"]")
                .sourceJson("{\"session_id\":\"session-1\"}")
                .status("active")
                .indexStatus("pending")
                .build();
        when(memoryItemMapper.upsertOnConflict(any())).thenReturn(1);
        when(memoryItemMapper.selectByUserAndTypeAndHash("user-1", "preference", "abc123"))
                .thenReturn(persisted);

        MemoryItemDTO result = repository.upsert(item);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("id-1");
        ArgumentCaptor<MemoryItemDTO> captor = ArgumentCaptor.forClass(MemoryItemDTO.class);
        verify(memoryItemMapper).upsertOnConflict(captor.capture());
        MemoryItemDTO upserted = captor.getValue();
        assertThat(upserted.getStatus()).isEqualTo("active");
        assertThat(upserted.getIndexStatus()).isEqualTo("pending");
        assertThat(upserted.getUpdatedAt()).isNotNull();
        assertThat(upserted.getTagsJson()).contains("ui");
        assertThat(upserted.getSourceJson()).contains("session-1");
    }

    @Test
    void shouldDeserializeJsonFieldsOnFindByUserIdAndStatus() {
        MemoryItemDTO storedItem = MemoryItemDTO.builder()
                .id("id-1")
                .userId("user-1")
                .type("fact")
                .content("User works at NTU")
                .contentHash("hash1")
                .tagsJson("[\"workplace\"]")
                .sourceJson("{\"session_id\":\"session-1\"}")
                .status("active")
                .indexStatus("indexed")
                .build();
        when(memoryItemMapper.selectByUserIdAndStatus("user-1", "active"))
                .thenReturn(List.of(storedItem));

        List<MemoryItemDTO> results = repository.findByUserIdAndStatus("user-1", "active");

        assertThat(results).hasSize(1);
        MemoryItemDTO result = results.get(0);
        assertThat(result.getTags()).containsExactly("workplace");
        assertThat(result.getSource()).containsEntry("session_id", "session-1");
    }

    @Test
    void shouldUpdateIndexStatus() {
        when(memoryItemMapper.updateIndexStatus("id-1", "indexed")).thenReturn(1);

        boolean updated = repository.updateIndexStatus("id-1", "indexed");

        assertThat(updated).isTrue();
    }

    @Test
    void shouldReturnFalseWhenIndexStatusUpdateFails() {
        when(memoryItemMapper.updateIndexStatus("id-1", "indexed")).thenReturn(0);

        boolean updated = repository.updateIndexStatus("id-1", "indexed");

        assertThat(updated).isFalse();
    }

    @Test
    void shouldUpdateStatus() {
        when(memoryItemMapper.updateStatus("id-1", "archived")).thenReturn(1);

        boolean updated = repository.updateStatus("id-1", "archived");

        assertThat(updated).isTrue();
    }

    @Test
    void shouldReturnEmptyWhenNoItemsFound() {
        when(memoryItemMapper.selectByUserIdAndStatus("user-1", "active"))
                .thenReturn(List.of());

        List<MemoryItemDTO> results = repository.findByUserIdAndStatus("user-1", "active");

        assertThat(results).isEmpty();
    }

    @Test
    void shouldDeserializeNullJsonFieldsAsEmptyDefaults() {
        MemoryItemDTO storedItem = MemoryItemDTO.builder()
                .id("id-1")
                .userId("user-1")
                .type("fact")
                .content("A fact")
                .contentHash("hash1")
                .tagsJson(null)
                .sourceJson("")
                .status("active")
                .indexStatus("pending")
                .build();
        when(memoryItemMapper.selectByUserIdAndStatus("user-1", "active"))
                .thenReturn(List.of(storedItem));

        List<MemoryItemDTO> results = repository.findByUserIdAndStatus("user-1", "active");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTags()).isEmpty();
        assertThat(results.get(0).getSource()).isEmpty();
    }

    @Test
    void shouldNormalizeNullTagsAndSourceOnUpsert() {
        MemoryItemDTO item = MemoryItemDTO.builder()
                .userId("user-1")
                .type("fact")
                .content("A fact")
                .contentHash("hash1")
                .tags(null)
                .source(null)
                .build();

        MemoryItemDTO persisted = MemoryItemDTO.builder()
                .id("id-1")
                .userId("user-1")
                .type("fact")
                .content("A fact")
                .contentHash("hash1")
                .tagsJson("[]")
                .sourceJson("{}")
                .status("active")
                .indexStatus("pending")
                .build();
        when(memoryItemMapper.upsertOnConflict(any())).thenReturn(1);
        when(memoryItemMapper.selectByUserAndTypeAndHash("user-1", "fact", "hash1"))
                .thenReturn(persisted);

        repository.upsert(item);

        ArgumentCaptor<MemoryItemDTO> captor = ArgumentCaptor.forClass(MemoryItemDTO.class);
        verify(memoryItemMapper).upsertOnConflict(captor.capture());
        MemoryItemDTO upserted = captor.getValue();
        assertThat(upserted.getTagsJson()).isEqualTo("[]");
        assertThat(upserted.getSourceJson()).isEqualTo("{}");
    }
}
