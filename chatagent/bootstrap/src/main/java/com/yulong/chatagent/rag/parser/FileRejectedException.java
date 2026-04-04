package com.yulong.chatagent.rag.parser;

/**
 * Raised when a file is rejected before entering the parsing pipeline.
 */
public class FileRejectedException extends RuntimeException {

    public FileRejectedException(String message) {
        super(message);
    }
}
