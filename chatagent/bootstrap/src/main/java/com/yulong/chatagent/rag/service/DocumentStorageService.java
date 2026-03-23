package com.yulong.chatagent.rag.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Stores and resolves uploaded source documents on the local file system.
 * <p>
 * Implementations are responsible for mapping logical document identifiers to
 * a stable storage layout that later ingestion jobs can read from.
 */
public interface DocumentStorageService {

    /**
     * Saves an uploaded file under the storage layout used by chat-session file attachments.
     *
     * @param sessionId current chat session identifier
     * @param sessionFileId logical session-file identifier
     * @param file uploaded file
     * @return stored relative file path
     * @throws IOException if the file cannot be written
     */
    String saveChatSessionFile(String sessionId, String sessionFileId, MultipartFile file) throws IOException;

    /**
     * Deletes one stored file.
     *
     * @param filePath stored relative file path
     * @throws IOException if deletion fails
     */
    void deleteFile(String filePath) throws IOException;

    /**
     * Resolves a stored relative path to an absolute file-system path.
     *
     * @param filePath stored relative file path
     * @return absolute file path
     */
    Path getFilePath(String filePath);

    /**
     * Checks whether a stored file currently exists.
     *
     * @param filePath stored relative file path
     * @return {@code true} when the target file exists
     */
    boolean fileExists(String filePath);
}
