package com.yulong.chatagent.agent.tools;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Optional tool offering guarded file-system access under the current workspace.
 * <p>
 * This tool is intentionally not registered as a Spring bean right now.
 */
@Slf4j
public class FileSystemTools implements Tool {

    private static final String BASE_DIRECTORY = System.getProperty("user.dir");

    @Override
    public String getName() {
        return "fileSystemTool";
    }

    @Override
    public String getDescription() {
        return "Provide safe file-system operations such as reading, writing, listing, and deleting files.";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    /**
     * Reads one file from the allowed workspace area.
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "readFile",
            description = "Read the content of a file relative to the current workspace directory."
    )
    public String readFile(String filePath) {
        try {
            Path path = validateAndResolvePath(filePath);

            if (!Files.exists(path)) {
                return "Error: file does not exist - " + filePath;
            }
            if (!Files.isRegularFile(path)) {
                return "Error: path is not a file - " + filePath;
            }

            String content = Files.readString(path);
            log.info("File read successfully: {}", filePath);
            return "File content:\n" + content;
        } catch (SecurityException e) {
            log.error("Security error: {}", e.getMessage());
            return "Error: access denied - " + e.getMessage();
        } catch (IOException e) {
            log.error("Failed to read file: {}", filePath, e);
            return "Error: failed to read file - " + e.getMessage();
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            return "Error: operation failed - " + e.getMessage();
        }
    }

    /**
     * Creates or replaces a file with the provided content.
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "writeFile",
            description = "Write content to a file relative to the current workspace directory. Missing parent directories will be created."
    )
    public String writeFile(String filePath, String content) {
        try {
            Path path = validateAndResolvePath(filePath);
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                log.info("Created directory: {}", parent);
            }

            Files.writeString(
                    path,
                    content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            log.info("File written successfully: {}", filePath);
            return "File written successfully: " + filePath;
        } catch (SecurityException e) {
            log.error("Security error: {}", e.getMessage());
            return "Error: access denied - " + e.getMessage();
        } catch (IOException e) {
            log.error("Failed to write file: {}", filePath, e);
            return "Error: failed to write file - " + e.getMessage();
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            return "Error: operation failed - " + e.getMessage();
        }
    }

    /**
     * Appends content to the target file, creating it when needed.
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "appendToFile",
            description = "Append content to a file relative to the current workspace directory. Missing parent directories will be created."
    )
    public String appendToFile(String filePath, String content) {
        try {
            Path path = validateAndResolvePath(filePath);
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                log.info("Created directory: {}", parent);
            }

            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("Content appended successfully: {}", filePath);
            return "Content appended successfully: " + filePath;
        } catch (SecurityException e) {
            log.error("Security error: {}", e.getMessage());
            return "Error: access denied - " + e.getMessage();
        } catch (IOException e) {
            log.error("Failed to append to file: {}", filePath, e);
            return "Error: failed to append content - " + e.getMessage();
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            return "Error: operation failed - " + e.getMessage();
        }
    }

    /**
     * Lists files and subdirectories inside a directory.
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "listFiles",
            description = "List files and subdirectories in a path relative to the current workspace directory. Empty input lists the workspace root."
    )
    public String listFiles(String directoryPath) {
        try {
            Path path;
            if (directoryPath == null || directoryPath.trim().isEmpty()) {
                path = Paths.get(BASE_DIRECTORY);
            } else {
                path = validateAndResolvePath(directoryPath);
            }

            if (!Files.exists(path)) {
                return "Error: directory does not exist - " + directoryPath;
            }
            if (!Files.isDirectory(path)) {
                return "Error: path is not a directory - " + directoryPath;
            }

            List<String> items;
            try (Stream<Path> paths = Files.list(path)) {
                items = paths
                        .map(p -> {
                            String name = p.getFileName().toString();
                            if (Files.isDirectory(p)) {
                                return "[DIR] " + name;
                            }
                            try {
                                long size = Files.size(p);
                                return "[FILE] " + name + " (" + formatFileSize(size) + ")";
                            } catch (IOException e) {
                                return "[FILE] " + name;
                            }
                        })
                        .sorted()
                        .collect(Collectors.toList());
            }

            if (items.isEmpty()) {
                return "Directory is empty: " + directoryPath;
            }

            log.info("Directory listed successfully: {}", directoryPath);
            return "Directory content (" + directoryPath + "):\n" + String.join("\n", items);
        } catch (SecurityException e) {
            log.error("Security error: {}", e.getMessage());
            return "Error: access denied - " + e.getMessage();
        } catch (IOException e) {
            log.error("Failed to list directory: {}", directoryPath, e);
            return "Error: failed to list directory - " + e.getMessage();
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            return "Error: operation failed - " + e.getMessage();
        }
    }

    /**
     * Deletes a file or recursively deletes a directory under the workspace.
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "deleteFile",
            description = "Delete a file or directory relative to the current workspace directory."
    )
    public String deleteFile(String path) {
        try {
            Path filePath = validateAndResolvePath(path);

            if (!Files.exists(filePath)) {
                return "Error: file or directory does not exist - " + path;
            }

            if (Files.isDirectory(filePath)) {
                try (Stream<Path> paths = Files.walk(filePath)) {
                    paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete path: {}", p, e);
                        }
                    });
                }
                log.info("Directory deleted successfully: {}", path);
                return "Directory deleted successfully: " + path;
            }

            Files.delete(filePath);
            log.info("File deleted successfully: {}", path);
            return "File deleted successfully: " + path;
        } catch (SecurityException e) {
            log.error("Security error: {}", e.getMessage());
            return "Error: access denied - " + e.getMessage();
        } catch (IOException e) {
            log.error("Failed to delete path: {}", path, e);
            return "Error: failed to delete path - " + e.getMessage();
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            return "Error: operation failed - " + e.getMessage();
        }
    }

    /**
     * Creates a directory and any missing parent directories.
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "createDirectory",
            description = "Create a directory relative to the current workspace directory. Missing parent directories will be created."
    )
    public String createDirectory(String directoryPath) {
        try {
            Path path = validateAndResolvePath(directoryPath);

            if (Files.exists(path)) {
                if (Files.isDirectory(path)) {
                    return "Directory already exists: " + directoryPath;
                }
                return "Error: path already exists but is not a directory - " + directoryPath;
            }

            Files.createDirectories(path);
            log.info("Directory created successfully: {}", directoryPath);
            return "Directory created successfully: " + directoryPath;
        } catch (SecurityException e) {
            log.error("Security error: {}", e.getMessage());
            return "Error: access denied - " + e.getMessage();
        } catch (IOException e) {
            log.error("Failed to create directory: {}", directoryPath, e);
            return "Error: failed to create directory - " + e.getMessage();
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            return "Error: operation failed - " + e.getMessage();
        }
    }

    /**
     * Resolves a path and blocks directory traversal outside the workspace root.
     */
    private Path validateAndResolvePath(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("File path cannot be empty");
        }

        Path basePath = Paths.get(BASE_DIRECTORY).toAbsolutePath().normalize();
        Path resolvedPath = basePath.resolve(filePath).toAbsolutePath().normalize();
        if (!resolvedPath.startsWith(basePath)) {
            throw new SecurityException("Directory traversal attempt blocked: " + filePath);
        }

        return resolvedPath;
    }

    /**
     * Formats byte counts into a readable file-size string.
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
}
