package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.contract.ActionRisk;
import com.yulong.chatagent.agent.runtime.contract.IntentLabel;
import com.yulong.chatagent.agent.runtime.contract.SourceNeed;
import com.yulong.chatagent.agent.runtime.contract.SourceReferenceClassifier;
import com.yulong.chatagent.agent.runtime.contract.TimeSensitivity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Owns deterministic current-turn signals used as policy constraints, never as confidence. */
@Component
public class IntentSignalAnalyzer {

    private static final Pattern CONTEXT_DEPENDENT = Pattern.compile(
            "(?i)\\b(it|that|this|those|these|same|previous|earlier|continue|what about|how about"
                    + "|current|now|latest|owner|room|invite|handoff|values)\\b"
                    + "|\\b(?:who(?:'s|\\s+is)\\s+on\\s+point|on\\s+point|point\\s+person"
                    + "|who\\s+(?:owns|handles|has|is\\s+handling)\\s+(?:it|this|that))\\b"
                    + "|它|这个|那个|这些|那些|同一个|继续|刚才|之前|谁负责|负责人|谁来处理|谁跟进|呢[？?]?$"
    );
    private static final Pattern SUBSTANTIVE_TURN = Pattern.compile(
            "(?i)\\b(?:how|what|who|where|when|why|continue|tell|show|summarize|explain|help)\\b"
                    + "|[?？]|怎么|什么|谁|哪里|为什么|继续|告诉|总结|解释|帮"
    );
    private static final Pattern TOPIC_SWITCH = Pattern.compile(
            "(?i)^(?:no[,，]?\\s*)?(?:actually|instead|i mean|rather|new question|different question|forget that|switch topic)[:：,，]?\\s*"
                    + "|^(?:(?:不对|不是)[,，]?\\s*(?:我指的是|我的意思是)?|我指的是|我的意思是|改问|换个问题|另一个问题|别管刚才|重新问|另外想问)[:：,，]?\\s*"
    );
    private static final Pattern MULTI_INTENT = Pattern.compile(
            "(?i)\\b(?:and also|as well as|both|plus|two things|first.+then)\\b"
                    + "|同时|以及|并且|都要|两个问题|一边.+一边"
    );
    private static final Pattern WRITE_ACTION = Pattern.compile(
            "(?i)\\b(?:create|update|change|delete|remove|submit|approve|reject|reset)\\b"
                    + "|创建|更新|修改|删除|移除|提交|批准|拒绝|重置"
    );
    private static final Pattern EXTERNAL_ACTION = Pattern.compile(
            "(?i)\\b(?:send|email|publish|book|purchase|buy|pay|invite|notify|deploy)\\b"
                    + "|发送|邮件|发布|预订|购买|付款|邀请|通知|部署"
    );
    private static final Pattern CONFIRMED = Pattern.compile(
            "(?i)\\b(?:confirm|confirmed|yes[, ]+do it|go ahead|proceed)\\b"
                    + "|确认执行|就这么做|继续执行|我确认"
    );
    private static final Pattern INFORMATIONAL_ACTION_CONTEXT = Pattern.compile(
            "(?i)\\b(?:how|what|who|when|where|why|which|can\\s+i|could\\s+i|may\\s+i"
                    + "|eligible|qualif(?:y|ies|ied))\\b"
                    + "|如何|怎么|怎样|哪些|什么|何时|哪里|资格|能否|可以吗"
    );
    private static final Pattern GENERAL_CHAT = Pattern.compile(
            "(?i)^(?:hi|hello|hey|thanks|thank you|good morning|good afternoon|how are you|help me brainstorm)\\b"
                    + "|^(?:你好|您好|谢谢|早上好|下午好|聊聊天|帮我想想)"
    );
    private static final Pattern OUT_OF_DOMAIN = Pattern.compile(
            "(?i)\\b(?:weather|football|basketball|recipe|horoscope|lottery|movie review)\\b"
                    + "|天气|足球|篮球|菜谱|星座|彩票|影评"
    );
    private static final Pattern EXPLICIT_WEB = Pattern.compile(
            "(?i)\\b(?:web|internet|online|search the web)\\b|联网|网上|网页"
    );

    private final SourceReferenceClassifier sourceReferenceClassifier;

    public IntentSignalAnalyzer(SourceReferenceClassifier sourceReferenceClassifier) {
        this.sourceReferenceClassifier = sourceReferenceClassifier;
    }

    public IntentTurnSignals analyze(String text) {
        String value = StringUtils.hasText(text) ? text.trim() : "";
        String normalized = value.toLowerCase(Locale.ROOT);
        SourceReferenceClassifier.SourceClassification classification =
                sourceReferenceClassifier.classify(value);
        ActionRisk risk = deriveRisk(normalized);
        boolean confirmed = CONFIRMED.matcher(normalized).find();
        boolean sourceConflict = classification.sessionFile()
                && classification.currentness()
                && EXPLICIT_WEB.matcher(normalized).find()
                && !classification.comparison();
        List<MissingDimension> missing = new ArrayList<>();
        if (risk != ActionRisk.READ_ONLY && !confirmed) {
            missing.add(MissingDimension.CONFIRMATION);
        }
        if (sourceConflict) {
            missing.add(MissingDimension.SOURCE);
        }
        List<IntentLabel> secondary = new ArrayList<>();
        if (classification.comparison()) {
            secondary.add(IntentLabel.COMPARE);
        }
        if (classification.currentness()) {
            secondary.add(IntentLabel.CURRENTNESS);
        }
        if (MULTI_INTENT.matcher(normalized).find()) {
            secondary.add(IntentLabel.MULTI_INTENT);
        }
        if (normalized.matches(".*(?:summari[sz]e|summary|总结|汇总).*")) {
            secondary.add(IntentLabel.SUMMARIZE);
        }
        if (normalized.matches(".*(?:extract|提取).*")) {
            secondary.add(IntentLabel.EXTRACT);
        }
        if (normalized.matches(".*(?:verify|核实|核对|确认).*")) {
            secondary.add(IntentLabel.VERIFY);
        }
        return new IntentTurnSignals(
                classification,
                sourceReferenceClassifier.deriveTimeSensitivity(classification),
                risk,
                CONTEXT_DEPENDENT.matcher(normalized).find(),
                isExplicitTopicSwitch(value),
                MULTI_INTENT.matcher(normalized).find(),
                sourceConflict,
                GENERAL_CHAT.matcher(normalized).find(),
                OUT_OF_DOMAIN.matcher(normalized).find(),
                EXPLICIT_WEB.matcher(normalized).find(),
                confirmed,
                missing,
                secondary
        );
    }

    public boolean isExplicitTopicSwitch(String text) {
        return StringUtils.hasText(text) && TOPIC_SWITCH.matcher(text.trim()).find();
    }

    public boolean isSubstantiveContextualTurn(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return CONTEXT_DEPENDENT.matcher(normalized).find()
                && SUBSTANTIVE_TURN.matcher(normalized).find();
    }

    public String stripTopicSwitchPrefix(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return TOPIC_SWITCH.matcher(text.trim()).replaceFirst("").trim();
    }

    public SourceNeed deriveSourceNeed(IntentTurnSignals signals,
                                       com.yulong.chatagent.intent.model.IntentKind kind) {
        return sourceReferenceClassifier.deriveSourceNeed(signals.sourceClassification(), kind);
    }

    public SourceNeed deriveSourceNeed(IntentTurnSignals signals,
                                       com.yulong.chatagent.intent.model.IntentKind kind,
                                       IntentRouteOutcome outcome) {
        if (outcome == IntentRouteOutcome.GENERAL_CHAT
                && signals.generalConversation()
                && !signals.explicitWebReference()
                && !signals.sourceClassification().sessionFile()
                && !signals.sourceClassification().knowledgeBase()) {
            return SourceNeed.NONE;
        }
        return deriveSourceNeed(signals, kind);
    }

    private ActionRisk deriveRisk(String normalized) {
        if (INFORMATIONAL_ACTION_CONTEXT.matcher(normalized).find()) {
            return ActionRisk.READ_ONLY;
        }
        if (EXTERNAL_ACTION.matcher(normalized).find()) {
            return ActionRisk.EXTERNAL_SIDE_EFFECT;
        }
        if (WRITE_ACTION.matcher(normalized).find()) {
            return ActionRisk.WRITE_ACTION;
        }
        return ActionRisk.READ_ONLY;
    }

    public record IntentTurnSignals(
            SourceReferenceClassifier.SourceClassification sourceClassification,
            TimeSensitivity timeSensitivity,
            ActionRisk actionRisk,
            boolean contextDependent,
            boolean explicitTopicSwitch,
            boolean multiIntentSignal,
            boolean sourceConflict,
            boolean generalConversation,
            boolean outOfDomain,
            boolean explicitWebReference,
            boolean explicitConfirmation,
            List<MissingDimension> missingDimensions,
            List<IntentLabel> secondaryIntents
    ) {
        public IntentTurnSignals {
            missingDimensions = missingDimensions == null ? List.of() : List.copyOf(missingDimensions);
            secondaryIntents = secondaryIntents == null ? List.of() : List.copyOf(secondaryIntents);
        }

    }
}
