package com.yulong.chatagent.rag.service.impl;

import com.yulong.chatagent.rag.service.DocumentStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * File-system implementation of {@link DocumentStorageService}.
 */
@Service
@Slf4j
public class DocumentStorageServiceImpl implements DocumentStorageService {

    @Value("${document.storage.base-path:./data/documents}")
    private String baseStoragePath;

    @Override
    public String saveChatSessionFile(String sessionId, String sessionFileId, MultipartFile file) throws IOException {
        return saveUploadedFile(Paths.get("sessions", sessionId, sessionFileId), file, sessionId, sessionFileId, "Chat-session");
    }

    @Override
    public String saveKnowledgeDocument(String knowledgeBaseId, String documentId, MultipartFile file) throws IOException {
        return saveUploadedFile(Paths.get("knowledge-bases", knowledgeBaseId, documentId), file, knowledgeBaseId, documentId, "Knowledge-base");
    }

    private String saveUploadedFile(Path relativeDirectory,
                                    MultipartFile file,
                                    String ownerId,
                                    String documentId,
                                    String logLabel) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        Path fileDir = Paths.get(baseStoragePath).resolve(relativeDirectory);
        Files.createDirectories(fileDir);

        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String uniqueFilename = UUID.randomUUID() + extension;

        Path targetPath = fileDir.resolve(uniqueFilename);
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        String relativePath = relativeDirectory.resolve(uniqueFilename).toString().replace("\\", "/");
        log.info("{} document stored successfully: ownerId={}, documentId={}, filename={}, path={}",
                logLabel, ownerId, documentId, originalFilename, relativePath);

        return relativePath;
    }

    @Override
    public void deleteFile(String filePath) throws IOException {
        Path fullPath = getFilePath(filePath);
        if (Files.exists(fullPath)) {
            Files.delete(fullPath);
            log.info("File deleted successfully: {}", filePath);

            // Best-effort cleanup for the document directory if it becomes empty.
            Path parentDir = fullPath.getParent();
            if (parentDir != null && Files.exists(parentDir)) {
                try {
                    Files.delete(parentDir);
                    log.info("Directory deleted successfully: {}", parentDir);
                } catch (IOException e) {
                    log.debug("Directory cleanup skipped: {}", parentDir);
                }
            }
        } else {
            log.warn("File does not exist, skip deletion: {}", filePath);
        }
    }

    @Override
    public Path getFilePath(String filePath) {
        return Paths.get(baseStoragePath, filePath);
    }

    @Override
    public boolean fileExists(String filePath) {
        Path fullPath = getFilePath(filePath);
        return Files.exists(fullPath) && Files.isRegularFile(fullPath);
    }
}
