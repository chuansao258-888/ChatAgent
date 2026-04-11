package com.yulong.chatagent.file.application;

import com.yulong.chatagent.access.ResourceAccessGuard;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.exception.ServiceException;
import com.yulong.chatagent.file.converter.ChatSessionFileConverter;
import com.yulong.chatagent.file.model.response.UploadChatSessionFileResponse;
import com.yulong.chatagent.file.model.vo.ChatSessionFileVO;
import com.yulong.chatagent.file.port.ChatSessionFileRepository;
import com.yulong.chatagent.file.port.FileChunkRepository;
import com.yulong.chatagent.rag.ingestion.FileIngestionService;
import com.yulong.chatagent.rag.application.DocumentStorageService;
import com.yulong.chatagent.rag.vector.milvus.SessionFileMilvusIndexer;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Default implementation of chat-session file attachment flows.
 */
@Service
@AllArgsConstructor
@Slf4j
public class ChatSessionFileFacadeServiceImpl implements ChatSessionFileFacadeService {

    private final ChatSessionFileRepository chatSessionFileRepository;
    private final FileChunkRepository fileChunkRepository;
    private final ChatSessionFileConverter chatSessionFileConverter;
    private final DocumentStorageService documentStorageService;
    private final SessionFileMilvusIndexer sessionFileMilvusIndexer;
    private final FileIngestionService fileIngestionService;
    private final ResourceAccessGuard resourceAccessGuard;

    @Override
    public ChatSessionFileVO[] getChatSessionFiles(String sessionId) {
        requireCurrentUserId();
        resourceAccessGuard.assertCanReadSession(UserContext.requireUser(), sessionId);

        List<ChatSessionFileVO> result = new ArrayList<>();
        for (ChatSessionFileDTO sessionFile : chatSessionFileRepository.findBySessionId(sessionId)) {
            result.add(chatSessionFileConverter.toVO(sessionFile));
        }

        return result.toArray(new ChatSessionFileVO[0]);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UploadChatSessionFileResponse uploadFile(String sessionId, MultipartFile file) {
        requireCurrentUserId();
        resourceAccessGuard.assertCanReadSession(UserContext.requireUser(), sessionId);
        if (file == null || file.isEmpty()) {
            throw new BizException("Uploaded file is empty");
        }

        LocalDateTime now = LocalDateTime.now();
        String sessionFileId = UUID.randomUUID().toString();
        String storedPath = null;

        try {
            storedPath = documentStorageService.saveChatSessionFile(sessionId, sessionFileId, file);
        } catch (IOException e) {
            throw new ServiceException("Failed to store uploaded file");
        }

        ChatSessionFileDTO sessionFile = ChatSessionFileDTO.builder()
                .id(sessionFileId)
                .sessionId(sessionId)
                .filename(defaultFilename(file.getOriginalFilename()))
                .originalFilename(defaultFilename(file.getOriginalFilename()))
                .mimeType(defaultMimeType(file.getContentType()))
                .sizeBytes(file.getSize())
                .storagePath(storedPath)
                .status("UPLOADED")
                .parseStatus("PENDING")
                .metadata("{}")
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            if (!chatSessionFileRepository.save(sessionFile)) {
                throw new BizException("Failed to create session file record");
            }

            scheduleIngestionAfterCommit(sessionId, sessionFile);

            return UploadChatSessionFileResponse.builder()
                .sessionFileId(sessionFile.getId())
                .sessionId(sessionId)
                .build();
        } catch (RuntimeException e) {
            cleanupStoredUpload(sessionFile.getId(), storedPath);
            throw e;
        }
    }

    private void scheduleIngestionAfterCommit(String sessionId, ChatSessionFileDTO sessionFile) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fileIngestionService.ingest(sessionId, sessionFile);
                }
            });
            return;
        }

        fileIngestionService.ingest(sessionId, sessionFile);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void detachFile(String sessionId, String sessionFileId) {
        requireCurrentUserId();
        resourceAccessGuard.assertCanReadSession(UserContext.requireUser(), sessionId);
        ChatSessionFileDTO sessionFile = requireOwnedSessionFile(sessionId, sessionFileId);

        fileChunkRepository.deleteBySessionFileId(sessionFileId);
        sessionFileMilvusIndexer.deleteBySessionFileId(sessionFileId);

        try {
            if (sessionFile.getStoragePath() != null) {
                documentStorageService.deleteFile(sessionFile.getStoragePath());
            }
        } catch (Exception e) {
            log.warn("Failed to delete stored session file, continue deleting record: sessionFileId={}, error={}",
                    sessionFileId, e.getMessage());
        }

        if (!chatSessionFileRepository.deleteById(sessionFileId)) {
            throw new BizException("Failed to delete session file");
        }
    }

    private void cleanupStoredUpload(String sessionFileId, String storedPath) {
        try {
            if (storedPath != null) {
                documentStorageService.deleteFile(storedPath);
            }
        } catch (Exception e) {
            log.warn("Failed to clean up stored session file after upload error: sessionFileId={}, error={}",
                    sessionFileId, e.getMessage());
        }
        chatSessionFileRepository.deleteById(sessionFileId);
    }

    private String defaultFilename(String originalFilename) {
        return (originalFilename == null || originalFilename.isBlank()) ? "uploaded-file" : originalFilename;
    }

    private String defaultMimeType(String mimeType) {
        return (mimeType == null || mimeType.isBlank()) ? "application/octet-stream" : mimeType;
    }

    private String requireCurrentUserId() {
        return UserContext.requireUser().getUserId();
    }

    private ChatSessionFileDTO requireOwnedSessionFile(String sessionId, String sessionFileId) {
        ChatSessionFileDTO sessionFile = chatSessionFileRepository.findById(sessionFileId);
        if (sessionFile == null || !sessionId.equals(sessionFile.getSessionId())) {
            throw new BizException("Session file not found: " + sessionFileId);
        }
        return sessionFile;
    }
}
