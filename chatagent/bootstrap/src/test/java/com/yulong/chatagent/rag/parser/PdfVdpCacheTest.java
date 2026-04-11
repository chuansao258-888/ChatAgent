package com.yulong.chatagent.rag.parser;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PdfVdpCacheTest {

    @Test
    void shouldPreservePdfPageCacheDigestFormat() {
        PdfVdpCache cache = new PdfVdpCache(null, 144f);
        PdfVdpCache.PageCacheContext context = cache.buildContext(
                Map.of(
                        "sessionId", "session-1",
                        "documentCacheKey", "knowledge-content:doc-1"
                ),
                PipelineSource.KNOWLEDGE,
                new VdpOptions(true, " zh-CN ", Map.of()),
                null
        );

        assertThat(cache.buildDigest(context, 0))
                .isEqualTo("pdf-page:knowledge-content:doc-1:0:1440:zh-cn:1");
    }

    @Test
    void shouldFallbackCacheDigestLanguagePartToDefault() {
        PdfVdpCache cache = new PdfVdpCache(null, 120f);
        PdfVdpCache.PageCacheContext context = cache.buildContext(
                Map.of("documentCacheKey", "session-file:file-1"),
                PipelineSource.SESSION,
                new VdpOptions(false, null, Map.of()),
                null
        );

        assertThat(cache.buildDigest(context, 3))
                .isEqualTo("pdf-page:session-file:file-1:3:1200:default:0");
    }
}
