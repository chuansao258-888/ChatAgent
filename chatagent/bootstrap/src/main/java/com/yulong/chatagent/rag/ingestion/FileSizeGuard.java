package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.parser.FileRejectedException;

/**
 * Entry-point file-size guard that runs before the full file is read into memory.
 */
public final class FileSizeGuard {

    static final long MAX_FILE_BYTES = 30L * 1024 * 1024;

    private FileSizeGuard() {
    }

    public static void guardBeforeRead(long fileSize, String filename) {
        if (fileSize > MAX_FILE_BYTES) {
            throw new FileRejectedException(
                    "File '%s' is %d bytes, exceeds %d byte limit"
                            .formatted(filename, fileSize, MAX_FILE_BYTES)
            );
        }
    }
}
