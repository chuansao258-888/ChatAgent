package com.yulong.chatagent.rag.parser;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns PDF page-cache context and digest generation so parser refactors can
 * keep cache key bytes stable across versions.
 */
final class PdfVdpCache {

    private final VdpPageCacheService pageCacheService;
    private final int renderDpiTenths;

    PdfVdpCache(VdpPageCacheService pageCacheService, float renderDpi) {
        this.pageCacheService = pageCacheService;
        this.renderDpiTenths = Math.round(Math.max(72f, renderDpi) * 10.0f);
    }

    PageCacheContext buildContext(Map<String, Object> rawOptions,
                                  PipelineSource pipelineSource,
                                  VdpOptions options,
                                  VdpEngine resolvedEngine) {
        return new PageCacheContext(
                pipelineSource,
                stringOption(rawOptions, "sessionId"),
                stringOption(rawOptions, "documentCacheKey"),
                resolvedEngine == null ? "unknown" : resolvedEngine.engineId(),
                resolvedEngine == null ? "default" : resolvedEngine.promptVersion(),
                options == null ? null : options.languageHint(),
                options != null && options.recognizeFormulas()
        );
    }

    VdpPageResult get(PageCacheContext pageCacheContext, int pageIndex) {
        if (!isEnabled(pageCacheContext)) {
            return null;
        }
        return pageCacheService.get(
                pageCacheContext.pipelineSource(),
                pageCacheContext.engineId(),
                pageCacheContext.promptVersion(),
                buildDigest(pageCacheContext, pageIndex),
                pageCacheContext.sessionId()
        );
    }

    void put(PageCacheContext pageCacheContext, int pageIndex, VdpPageResult result) {
        if (!isEnabled(pageCacheContext) || result == null) {
            return;
        }
        pageCacheService.put(
                pageCacheContext.pipelineSource(),
                pageCacheContext.engineId(),
                pageCacheContext.promptVersion(),
                buildDigest(pageCacheContext, pageIndex),
                pageCacheContext.sessionId(),
                result
        );
    }

    void putAll(PageCacheContext pageCacheContext, Map<Integer, VdpPageResult> pageResults) {
        if (!isEnabled(pageCacheContext) || pageResults == null || pageResults.isEmpty()) {
            return;
        }
        Map<String, VdpPageResult> entriesByDigest = new LinkedHashMap<>();
        pageResults.forEach((pageIndex, result) -> entriesByDigest.put(buildDigest(pageCacheContext, pageIndex), result));
        pageCacheService.putAll(
                pageCacheContext.pipelineSource(),
                pageCacheContext.engineId(),
                pageCacheContext.promptVersion(),
                entriesByDigest,
                pageCacheContext.sessionId()
        );
    }

    String buildDigest(PageCacheContext pageCacheContext, int pageIndex) {
        return "pdf-page:"
                + pageCacheContext.documentCacheKey()
                + ':'
                + pageIndex
                + ':'
                + renderDpiTenths
                + ':'
                + normalizeCachePart(pageCacheContext.languageHint())
                + ':'
                + (pageCacheContext.recognizeFormulas() ? '1' : '0');
    }

    private boolean isEnabled(PageCacheContext pageCacheContext) {
        return pageCacheService != null
                && pageCacheContext != null
                && StringUtils.hasText(pageCacheContext.documentCacheKey());
    }

    private String normalizeCachePart(String value) {
        if (!StringUtils.hasText(value)) {
            return "default";
        }
        return value.trim().toLowerCase();
    }

    private String stringOption(Map<String, Object> options, String key) {
        Object value = options == null ? null : options.get(key);
        return value == null ? null : value.toString();
    }

    record PageCacheContext(PipelineSource pipelineSource,
                            String sessionId,
                            String documentCacheKey,
                            String engineId,
                            String promptVersion,
                            String languageHint,
                            boolean recognizeFormulas) {
    }
}
