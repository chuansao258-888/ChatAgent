package com.yulong.chatagent.file.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.file.converter.ChatSessionFileConverter;
import com.yulong.chatagent.file.model.vo.ChatSessionFileVO;
import com.yulong.chatagent.file.port.ChatSessionFileRepository;
import com.yulong.chatagent.file.port.FileChunkRepository;
import com.yulong.chatagent.rag.application.DocumentStorageService;
import com.yulong.chatagent.rag.ingestion.FileIngestionService;
import com.yulong.chatagent.rag.vector.milvus.SessionFileMilvusIndexer;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSessionFileFacadeServiceImplTest {

    @Mock
    private ChatSessionFileRepository chatSessionFileRepository;

    @Mock
    private FileChunkRepository fileChunkRepository;

    @Mock
    private ChatSessionFileConverter chatSessionFileConverter;

    @Mock
    private DocumentStorageService documentStorageService;

    @Mock
    private SessionFileMilvusIndexer sessionFileMilvusIndexer;

    @Mock
    private FileIngestionService fileIngestionService;

    @Mock
    private ResourceAccessGuard resourceAccessGuard;

    private ChatSessionFileFacadeServiceImpl facadeService;

    @BeforeEach
    void setUp() {
        facadeService = new ChatSessionFileFacadeServiceImpl(
                chatSessionFileRepository,
                fileChunkRepository,
                chatSessionFileConverter,
                documentStorageService,
                sessionFileMilvusIndexer,
                fileIngestionService,
                resourceAccessGuard
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldReturnDirectChatSessionFileArray() {
        LoginUser user = LoginUser.builder()
                .userId("user-1")
                .role("user")
                .build();
        UserContext.set(user);

        ChatSessionFileDTO sessionFile = ChatSessionFileDTO.builder()
                .id("file-1")
                .sessionId("session-1")
                .filename("notes.pdf")
                .originalFilename("notes.pdf")
                .mimeType("application/pdf")
                .sizeBytes(2048L)
                .status("UPLOADED")
                .parseStatus("COMPLETED")
                .createdAt(LocalDateTime.now().minusMinutes(2))
                .updatedAt(LocalDateTime.now())
                .build();
        ChatSessionFileVO expected = ChatSessionFileVO.builder()
                .id("file-1")
                .filename("notes.pdf")
                .originalFilename("notes.pdf")
                .mimeType("application/pdf")
                .sizeBytes(2048L)
                .status("UPLOADED")
                .parseStatus("COMPLETED")
                .createdAt(sessionFile.getCreatedAt())
                .updatedAt(sessionFile.getUpdatedAt())
                .build();

        when(resourceAccessGuard.assertCanReadSession(user, "session-1")).thenReturn(ChatSessionDTO.builder()
                .id("session-1")
                .userId("user-1")
                .build());
        when(chatSessionFileRepository.findBySessionId("session-1")).thenReturn(List.of(sessionFile));
        when(chatSessionFileConverter.toVO(sessionFile)).thenReturn(expected);

        ChatSessionFileVO[] response = facadeService.getChatSessionFiles("session-1");

        assertThat(response).containsExactly(expected);
        verify(resourceAccessGuard).assertCanReadSession(user, "session-1");
        verify(chatSessionFileRepository).findBySessionId("session-1");
    }
}
