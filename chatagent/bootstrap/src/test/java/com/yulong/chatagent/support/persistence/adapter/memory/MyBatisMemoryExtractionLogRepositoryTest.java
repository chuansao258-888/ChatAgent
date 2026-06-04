package com.yulong.chatagent.support.persistence.adapter.memory;

import com.yulong.chatagent.support.dto.MemoryExtractionLogDTO;
import com.yulong.chatagent.support.persistence.mapper.MemoryExtractionLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyBatisMemoryExtractionLogRepositoryTest {

    @Mock
    private MemoryExtractionLogMapper mapper;

    private MyBatisMemoryExtractionLogRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MyBatisMemoryExtractionLogRepository(mapper);
    }

    @Test
    void shouldFindByRange() {
        MemoryExtractionLogDTO expected = MemoryExtractionLogDTO.builder()
                .id("log-1")
                .userId("user-1")
                .sessionId("session-1")
                .seqStartNo(1L)
                .seqEndNo(8L)
                .status("completed")
                .build();
        when(mapper.selectByRange("session-1", 1L, 8L)).thenReturn(expected);

        MemoryExtractionLogDTO result = repository.findByRange("session-1", 1L, 8L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("log-1");
        assertThat(result.getStatus()).isEqualTo("completed");
    }

    @Test
    void shouldReturnNullWhenRangeNotFound() {
        when(mapper.selectByRange("session-1", 1L, 8L)).thenReturn(null);

        MemoryExtractionLogDTO result = repository.findByRange("session-1", 1L, 8L);

        assertThat(result).isNull();
    }

    @Test
    void shouldInsertWithTimestamps() {
        when(mapper.insert(any())).thenReturn(1);

        MemoryExtractionLogDTO log = MemoryExtractionLogDTO.builder()
                .userId("user-1")
                .sessionId("session-1")
                .seqStartNo(1L)
                .seqEndNo(8L)
                .status("processing")
                .build();

        MemoryExtractionLogDTO result = repository.insert(log);

        assertThat(result).isNotNull();
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();
        ArgumentCaptor<MemoryExtractionLogDTO> captor = ArgumentCaptor.forClass(MemoryExtractionLogDTO.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("processing");
    }

    @Test
    void shouldUpdateStatus() {
        when(mapper.updateStatus("log-1", "completed", null)).thenReturn(1);

        boolean updated = repository.updateStatus("log-1", "completed", null);

        assertThat(updated).isTrue();
    }

    @Test
    void shouldUpdateStatusWithError() {
        when(mapper.updateStatus("log-1", "failed", "JSON parse error")).thenReturn(1);

        boolean updated = repository.updateStatus("log-1", "failed", "JSON parse error");

        assertThat(updated).isTrue();
    }

    @Test
    void shouldReturnFalseWhenStatusUpdateAffectsNoRows() {
        when(mapper.updateStatus("log-1", "completed", null)).thenReturn(0);

        boolean updated = repository.updateStatus("log-1", "completed", null);

        assertThat(updated).isFalse();
    }
}
