package com.yulong.chatagent.rag.parser;

/**
 * Normalized file-type detection result produced before parser selection.
 */
public record DetectedFileType(
        String extension,
        String mimeType,
        boolean rejected,
        String rejectionReason
) {

    public DetectedFileType {
        extension = extension == null ? "" : extension.toLowerCase();
        mimeType = mimeType == null ? "application/octet-stream" : mimeType.toLowerCase();
        rejectionReason = rejectionReason == null ? "" : rejectionReason;
    }

    public static DetectedFileType accepted(String extension, String mimeType) {
        return new DetectedFileType(extension, mimeType, false, "");
    }

    public static DetectedFileType rejected(String extension, String mimeType, String rejectionReason) {
        return new DetectedFileType(extension, mimeType, true, rejectionReason);
    }

    public boolean isMarkdown() {
        return "md".equals(extension)
                || "markdown".equals(extension)
                || "text/markdown".equals(mimeType)
                || "text/x-markdown".equals(mimeType);
    }

    public boolean isPdf() {
        return "pdf".equals(extension) || "application/pdf".equals(mimeType);
    }

    public boolean isImage() {
        return mimeType.startsWith("image/")
                || "png".equals(extension)
                || "jpg".equals(extension)
                || "jpeg".equals(extension)
                || "gif".equals(extension)
                || "webp".equals(extension)
                || "bmp".equals(extension);
    }

    public boolean isWord() {
        return "docx".equals(extension)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mimeType);
    }

    public boolean isPowerPoint() {
        return "pptx".equals(extension)
                || "application/vnd.openxmlformats-officedocument.presentationml.presentation".equals(mimeType);
    }

    public boolean isSpreadsheet() {
        return "xlsx".equals(extension)
                || "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mimeType);
    }

    public boolean isHtml() {
        return "html".equals(extension) || "htm".equals(extension)
                || "text/html".equals(mimeType);
    }
}
