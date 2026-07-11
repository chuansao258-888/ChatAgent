package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.contract.ActionRisk;
import com.yulong.chatagent.agent.runtime.contract.SourceNeed;
import com.yulong.chatagent.agent.runtime.contract.SourceReferenceClassifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentSignalAnalyzerTest {

    private final IntentSignalAnalyzer analyzer =
            new IntentSignalAnalyzer(new SourceReferenceClassifier());

    @Test
    void shouldClassifyFileCurrentnessAndGreetingSourceSemantics() {
        assertThat(source("请总结上传的briefing.docx。", IntentRouteOutcome.GENERAL_CHAT))
                .isEqualTo(SourceNeed.FILE);
        assertThat(source("What is currently popular in remote learning?",
                IntentRouteOutcome.GENERAL_CHAT)).isEqualTo(SourceNeed.WEB);
        assertThat(source("Hello, how are you today?", IntentRouteOutcome.GENERAL_CHAT))
                .isEqualTo(SourceNeed.NONE);
    }

    @Test
    void shouldDistinguishActionQuestionsFromExecutionRequests() {
        assertThat(analyzer.analyze("Who qualifies to submit Travel Expense?").actionRisk())
                .isEqualTo(ActionRisk.READ_ONLY);
        assertThat(analyzer.analyze("哪些人可以提交差旅报销？").actionRisk())
                .isEqualTo(ActionRisk.READ_ONLY);
        assertThat(analyzer.analyze("Deploy the access-policy update.").actionRisk())
                .isEqualTo(ActionRisk.EXTERNAL_SIDE_EFFECT);
        assertThat(analyzer.analyze("部署访问制度更新。").actionRisk())
                .isEqualTo(ActionRisk.EXTERNAL_SIDE_EFFECT);
    }

    @Test
    void shouldStripEnglishAndChineseCorrectionPrefixes() {
        assertThat(analyzer.stripTopicSwitchPrefix("No, I mean Travel Expense"))
                .isEqualTo("Travel Expense");
        assertThat(analyzer.stripTopicSwitchPrefix("不对，我指的是差旅报销"))
                .isEqualTo("差旅报销");
    }

    private SourceNeed source(String text, IntentRouteOutcome outcome) {
        IntentSignalAnalyzer.IntentTurnSignals signals = analyzer.analyze(text);
        return analyzer.deriveSourceNeed(signals, null, outcome);
    }
}
