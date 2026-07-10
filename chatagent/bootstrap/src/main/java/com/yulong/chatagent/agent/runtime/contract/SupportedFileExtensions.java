package com.yulong.chatagent.agent.runtime.contract;

/**
 * Single authority for the supported file-extension regex fragment used across
 * the contract package.
 *
 * <p>This is the contract-side successor of the extension set in
 * {@code FileTypeDetector}; the legacy keyword gate in {@code AgentThinkingEngine}
 * still has its own copy and is removed only in Phase 3. Until then, both exist
 * intentionally — this class is the authority for contract-side classification
 * and extraction only.</p>
 */
final class SupportedFileExtensions {

    private SupportedFileExtensions() {
    }

    /**
     * Non-capturing regex fragment matching a supported file extension (without
     * the leading dot or filename). Embed inside a filename pattern.
     */
    static final String EXTENSION_GROUP = "(?:md|markdown|txt|pdf|docx?|pptx|xlsx|html?|csv|png|jpe?g|gif|webp|bmp)";
}
