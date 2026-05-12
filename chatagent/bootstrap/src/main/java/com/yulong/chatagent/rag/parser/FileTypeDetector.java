package com.yulong.chatagent.rag.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * Lightweight file-type detection that relies on upload metadata plus a small magic prefix.
 */
@Component
@Slf4j
public class FileTypeDetector {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("md", "markdown", "txt", "pdf", "doc", "docx", "pptx", "xlsx");
    private static final Set<String> SESSION_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
    private static final Set<String> REJECTED_EXTENSIONS = Set.of(
            "exe", "dll", "bin", "zip", "rar", "7z", "tar", "gz",
            // Raster image formats are handled by the imageCandidate branch above.
            "svg", "ico",
            "mp3", "wav", "flac", "aac", "ogg",
            "mp4", "mov", "avi", "mkv", "wmv",
            "xls", "ppt",
            "jar", "war", "class", "apk", "iso", "db", "sqlite"
    );
    private static final Set<String> REJECTED_MIMES = Set.of(
            "application/zip",
            "application/x-zip-compressed",
            "application/x-rar-compressed",
            "application/x-7z-compressed",
            "application/java-archive",
            "application/x-msdownload"
    );
    private static final Set<String> SUPPORTED_MIMES = Set.of(
            "text/markdown",
            "text/x-markdown",
            "text/plain",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );
    private static final Set<String> SESSION_IMAGE_MIMES = Set.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/bmp"
    );

    private final Tika tika = new Tika();

    public DetectedFileType detect(byte[] prefix, String originalFilename, String declaredMimeType) {
        return detect(prefix, originalFilename, declaredMimeType, PipelineSource.KNOWLEDGE);
    }

    public DetectedFileType detect(byte[] prefix,
                                   String originalFilename,
                                   String declaredMimeType,
                                   PipelineSource pipelineSource) {
        String extension = extensionOf(originalFilename);
        String normalizedDeclaredMime = normalizeMime(declaredMimeType);
        String detectedMime = detectMime(prefix, originalFilename);
        PipelineSource source = pipelineSource == null ? PipelineSource.KNOWLEDGE : pipelineSource;

        boolean imageCandidate = SESSION_IMAGE_EXTENSIONS.contains(extension)
                || SESSION_IMAGE_MIMES.contains(normalizedDeclaredMime)
                || SESSION_IMAGE_MIMES.contains(detectedMime)
                || (StringUtils.hasText(detectedMime) && detectedMime.startsWith("image/"))
                || (StringUtils.hasText(normalizedDeclaredMime) && normalizedDeclaredMime.startsWith("image/"));

        if (imageCandidate) {
            if (source == PipelineSource.KNOWLEDGE) {
                return DetectedFileType.rejected(
                        extension,
                        preferredMimeForImage(normalizedDeclaredMime, detectedMime),
                        "Knowledge-base uploads do not accept standalone images; please use session upload or convert the image into a document first"
                );
            }
            if (isStrongBinaryMime(detectedMime) && !isCompatibleImage(extension, detectedMime)) {
                return DetectedFileType.rejected(extension, detectedMime, "File content does not match image extension " + extension);
            }
            return DetectedFileType.accepted(extension, preferredMimeForImage(normalizedDeclaredMime, detectedMime));
        }

        if (REJECTED_EXTENSIONS.contains(extension)) {
            return DetectedFileType.rejected(extension, detectedMime, "Unsupported file extension: " + extension);
        }

        if (SUPPORTED_EXTENSIONS.contains(extension)) {
            if (isStrongBinaryMime(detectedMime) && !isCompatibleWithExtension(extension, detectedMime)) {
                return DetectedFileType.rejected(extension, detectedMime, "File content does not match extension " + extension);
            }
            return DetectedFileType.accepted(extension, preferredMimeForExtension(extension, normalizedDeclaredMime, detectedMime));
        }

        if (SUPPORTED_MIMES.contains(detectedMime)) {
            return DetectedFileType.accepted(extension, detectedMime);
        }
        if (SUPPORTED_MIMES.contains(normalizedDeclaredMime)) {
            return DetectedFileType.accepted(extension, normalizedDeclaredMime);
        }

        String rejectionMime = StringUtils.hasText(detectedMime) ? detectedMime : normalizedDeclaredMime;
        if (isStrongBinaryMime(rejectionMime)) {
            return DetectedFileType.rejected(extension, rejectionMime, "Unsupported binary mime type: " + rejectionMime);
        }
        return DetectedFileType.rejected(extension, rejectionMime, "Unsupported file type");
    }

    private boolean isCompatibleImage(String extension, String detectedMime) {
        if (!StringUtils.hasText(detectedMime) || "application/octet-stream".equals(detectedMime)) {
            return true;
        }
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg".equals(detectedMime);
            case "png" -> "image/png".equals(detectedMime);
            case "gif" -> "image/gif".equals(detectedMime);
            case "webp" -> "image/webp".equals(detectedMime);
            case "bmp" -> "image/bmp".equals(detectedMime);
            default -> detectedMime.startsWith("image/");
        };
    }

    private String preferredMimeForImage(String declaredMimeType, String detectedMime) {
        if (StringUtils.hasText(detectedMime) && detectedMime.startsWith("image/")) {
            return detectedMime;
        }
        if (StringUtils.hasText(declaredMimeType) && declaredMimeType.startsWith("image/")) {
            return declaredMimeType;
        }
        return "image/png";
    }

    private String detectMime(byte[] prefix, String originalFilename) {
        try {
            if (prefix != null && prefix.length > 0) {
                return normalizeMime(tika.detect(prefix, originalFilename));
            }
            return normalizeMime(tika.detect(originalFilename));
        } catch (Exception e) {
            log.warn("File-type detection fallback triggered: filename={}, reason={}",
                    originalFilename, e.getMessage());
            return "application/octet-stream";
        }
    }

    private boolean isStrongBinaryMime(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return false;
        }
        return mimeType.startsWith("image/")
                || mimeType.startsWith("video/")
                || mimeType.startsWith("audio/")
                || REJECTED_MIMES.contains(mimeType);
    }

    private boolean isCompatibleWithExtension(String extension, String detectedMime) {
        if (!StringUtils.hasText(detectedMime)) {
            return true;
        }
        return switch (extension) {
            case "md", "markdown" -> detectedMime.startsWith("text/") || "application/octet-stream".equals(detectedMime);
            case "txt" -> "text/plain".equals(detectedMime) || "application/octet-stream".equals(detectedMime);
            case "pdf" -> "application/pdf".equals(detectedMime);
            case "doc" -> "application/msword".equals(detectedMime) || "application/octet-stream".equals(detectedMime);
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(detectedMime)
                    || "application/zip".equals(detectedMime)
                    || "application/x-zip-compressed".equals(detectedMime)
                    || "application/octet-stream".equals(detectedMime);
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation".equals(detectedMime)
                    || "application/zip".equals(detectedMime)
                    || "application/x-zip-compressed".equals(detectedMime)
                    || "application/octet-stream".equals(detectedMime);
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(detectedMime)
                    || "application/zip".equals(detectedMime)
                    || "application/x-zip-compressed".equals(detectedMime)
                    || "application/octet-stream".equals(detectedMime);
            default -> false;
        };
    }

    private String preferredMimeForExtension(String extension, String declaredMimeType, String detectedMime) {
        return switch (extension) {
            case "md", "markdown" -> "text/markdown";
            case "txt" -> "text/plain";
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> StringUtils.hasText(detectedMime) ? detectedMime : declaredMimeType;
        };
    }

    private String extensionOf(String originalFilename) {
        if (!StringUtils.hasText(originalFilename) || !originalFilename.contains(".")) {
            return "";
        }
        return originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeMime(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return "application/octet-stream";
        }
        return mimeType.toLowerCase(Locale.ROOT);
    }
}
