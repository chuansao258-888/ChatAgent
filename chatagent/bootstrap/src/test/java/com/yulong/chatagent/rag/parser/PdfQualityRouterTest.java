package com.yulong.chatagent.rag.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfQualityRouterTest {

    @Test
    void forceVisualTrackOverridesNarrativeNativeText() {
        PdfQualityRouter router = new PdfQualityRouter(1_000_000, 1, 1, true);

        PdfQualityRouter.PageRoutingDecision decision = router.decideRoute(
                "This public PDF page contains ordinary narrative text. It would normally fast-track.");

        assertThat(decision.isVisualTrack()).isTrue();
        assertThat(decision.reason()).isEqualTo("FORCED_VISUAL_TRACK");
    }

    @Test
    void defaultRoutingStillFastTracksNarrativeNativeText() {
        PdfQualityRouter router = new PdfQualityRouter(1_000_000, 1, 1);

        PdfQualityRouter.PageRoutingDecision decision = router.decideRoute(
                "This public PDF page contains ordinary narrative text. It should stay native by default.");

        assertThat(decision.isVisualTrack()).isFalse();
        assertThat(decision.reason()).isEqualTo("SHORT_NARRATIVE_FAST_TRACK");
    }
}
