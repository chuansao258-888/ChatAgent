package com.yulong.chatagent.agent.runtime.contract;

import com.yulong.chatagent.intent.model.IntentKind;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * Consolidated, typed classifier for source references, currentness, and
 * comparison/object targets in user text.
 *
 * <p>This is the contract-side successor of the session-file and extension
 * semantics that previously lived as inconsistent rules across
 * {@link QueryPlanBuilder} and {@link TurnExecutionContractBuilder}. The
 * session-file patterns are informed by the legacy keyword gate in
 * {@code AgentThinkingEngine.SESSION_FILE_REFERENCE} and the supported-extension
 * set in {@code FileTypeDetector}, but the legacy gate still runs independently
 * and is removed only in Phase 3. Until then, this classifier is the authority
 * for contract-side source classification only.</p>
 */
@Component
public class SourceReferenceClassifier {

    /**
     * Session-file references. Covers explicit upload/attachment verbs with a
     * document noun, determiner + file noun ("the/this/my file"), and filenames
     * with a supported extension. Mirrors the authoritative keyword gate in
     * {@code AgentThinkingEngine}.
     */
    private static final Pattern SESSION_FILE = Pattern.compile(
            // English: upload/attach as standalone verb (narrow; sent/shared alone are too broad)
            "(?i)\\b(?:i|we)\\s+(?:just\\s+)?(?:uploaded|attached)\\b"
                    // sent/shared only count when followed by a file/document noun
                    + "|(?i)\\b(?:i|we)\\s+(?:just\\s+)?(?:sent|shared)\\s+(?:the\\s+|a\\s+|an\\s+)?(?:file|files|document|documents|spreadsheet|spreadsheets|attachment|attachments|image|images|pdf|report|reports?|note|notes?|briefing|brief|source|sources?|csv|docx|xlsx)\\b"
                    + "|\\b(?:uploaded|attached)\\s+(?:file|files|document|documents|spreadsheet|spreadsheets|attachment|attachments|image|images|excel|pdf|report|reports?|note|notes?|briefing|brief|source|sources?|csv|docx|xlsx|txt|md)\\b"
                    + "|\\b(?:the|this|that|same|my|our|these|those)\\s+(?:uploaded|attached|session)?\\s*(?:attachment|file|document|pdf|image|photo|picture|spreadsheet|csv|docx|xlsx|note|briefing|brief|source|sources?)\\b"
                    + "|\\b(?:uploaded|attached|session|local)\\s+(?:file|files|document|note|notes?|source|sources?)\\b"
                    // English: filename with a supported extension (FileTypeDetector set, image-aware)
                    + "|\\b[\\w.\\-]+\\." + SupportedFileExtensions.EXTENSION_GROUP + "\\b"
                    // Chinese upload expressions
                    + "|(?:我|我们)(?:刚才)?上传(?:了|的)?|刚才上传(?:了|的)?"
                    + "|上传的?(?:文件|表格|文档|图片|附件|照片)"
                    + "|(?:这个|该|我的|我们的|这些|那些|刚才的|上述)(?:附件|文件|文档|图片|照片|表格)"
                    + "|(?:查看|看|阅读|分析|总结).{0,4}(?:附件|文件|文档|图片|照片|表格)"
    );

    /**
     * Explicit knowledge-base references. "policy" is intentionally NOT matched
     * here because it is a common content noun, not a source reference — matching
     * it would route "monetary policy" questions to KB. Only explicit source
     * markers (knowledge base, KB, source/reference pack, handbook, runbook)
     * qualify.
     */
    private static final Pattern KNOWLEDGE_BASE = Pattern.compile(
            "(?i)\\b(?:knowledge\\s*base|\\bkb\\b|source\\s+pack|reference\\s+pack|handbook|runbook)\\b"
                    + "|知识库|手册|参考资料|引用来源"
    );

    /**
     * Currentness / web references. Bare content nouns (price/version/score/stock)
     * are NOT matched because they appear in ordinary questions without implying a
     * live-web need. Currentness requires a temporal/qualifier word that signals the
     * user wants fresh data. "now" is excluded (too generic — "reset it now" is not a
     * web query). "today" alone is excluded from WEB precedence because an
     * uploaded-file reference should win (see deriveSourceNeed precedence).
     */
    private static final Pattern CURRENTNESS = Pattern.compile(
            "(?i)\\b(?:latest|current|newest|up\\s*to\\s*date|recent|news|today)\\b"
                    + "|最新|当前|实时|新闻|今天"
    );

    /** Comparison operation tokens. */
    private static final Pattern COMPARISON = Pattern.compile(
            "(?i)\\b(?:compare|comparison|versus|vs\\.?|contrast|difference|differ|differences?)\\b"
                    + "|对比|比较|区别|异同"
    );

    /**
     * Comparison operands: captures BOTH sides of "A with/and/vs B" (English) and
     * "A 和/与/跟/对比 B" (Chinese), so preservation can verify neither side is
     * dropped. Also captures quoted strings and hyphenated identifiers as objects.
     */
    private static final Pattern COMPARISON_OPERAND_LEFT = Pattern.compile(
            // English: "A with B" — the operand BEFORE the connector (one noun phrase, optional trailing id)
            "(?i)\\b([\\p{L}][\\p{L}\\p{N}'-]{1,40}(?:\\s+[\\p{L}\\p{N}]{1,20})?)\\s+(?:with|and|or|versus|vs\\.?|compared\\s+to|contrast)\\b"
    );
    private static final Pattern COMPARISON_OPERAND_RIGHT = Pattern.compile(
            // English: "with B" — the operand AFTER the connector (one noun phrase, optional trailing id)
            "(?i)\\b(?:with|and|or|versus|vs\\.?|compared\\s+to|contrast)\\s+([\\p{L}][\\p{L}\\p{N}'-]{1,40}(?:\\s+[\\p{L}\\p{N}]{1,20})?)"
    );
    private static final Pattern COMPARISON_OPERAND_CN = Pattern.compile(
            // Chinese: A 和/与/跟 B — capture both sides as CJK noun runs (allow a trailing
            // latin letter/digit so "政策A 和 政策B" matches). Strip a leading comparison verb
            // (比较/对比) from operand 1 since it is the operator, not the object.
            "(?:比较|对比)?([\\u4e00-\\u9fff]{1,12}[\\p{L}\\p{N}]?)(?:和|与|跟)([\\u4e00-\\u9fff]{1,12}[\\p{L}\\p{N}]?)"
    );
    private static final Pattern QUOTED_OR_HYPHENATED = Pattern.compile(
            "\"([^\"]{2,80})\"|`([^`]{2,80})`|(?<!\\w)([\\p{L}]{2,20}[-/][\\p{L}\\p{N}]{1,20})(?!\\w)"
    );

    /**
     * Filename pattern (most specific file reference). Checked first so a real
     * filename wins over a looser upload-noun phrase that appears earlier.
     */
    private static final Pattern FILENAME_REFERENCE = Pattern.compile(
            "([\\w.\\-]+\\." + SupportedFileExtensions.EXTENSION_GROUP + ")"
    );

    /**
     * Looser file-reference pattern: upload/attach verb + noun, or determiner +
     * file noun, or Chinese upload expressions. Used only when no filename is found.
     */
    private static final Pattern UPLOAD_NOUN_REFERENCE = Pattern.compile(
            "(?i)(?:uploaded|attached)\\s+((?:file|files|document|documents|spreadsheet|spreadsheets|attachment|attachments|image|images|excel|pdf|report|reports?|note|notes?|briefing|brief|source|sources?|csv|docx|xlsx|txt|md))"
                    + "|(?i)(?:the|this|that|same|my|our|these|those)\\s+((?:uploaded\\s+|attached\\s+|session\\s+)?(?:attachment|file|document|pdf|spreadsheet|csv|docx|xlsx|note|briefing|brief|source))"
                    + "|上传的?((?:文件|表格|文档|图片|附件|照片))"
                    + "|(?:这个|该|我的|我们的|这些|那些|刚才的|上述)((?:附件|文件|文档|图片|照片|表格))"
    );

    /**
     * Classify the source references in the given text.
     *
     * @param text the raw user input
     * @return a non-null classification result
     */
    public SourceClassification classify(String text) {
        if (!StringUtils.hasText(text)) {
            return new SourceClassification(false, false, false, false, List.of());
        }
        boolean sessionFile = SESSION_FILE.matcher(text).find();
        boolean kb = KNOWLEDGE_BASE.matcher(text).find();
        boolean currentness = CURRENTNESS.matcher(text).find();
        boolean comparison = COMPARISON.matcher(text).find();
        List<String> targets = extractComparisonOperands(text);
        return new SourceClassification(sessionFile, kb, currentness, comparison, targets);
    }

    /**
     * Derive the dominant {@link SourceNeed} from a classification and the routing
     * intent kind.
     *
     * <p>KB intent wins over session-file when both are present only if the user
     * did NOT also name a file; when both are named it is MIXED. TOOL intent owns
     * its own data acquisition, so it never derives a retrieval source need.</p>
     */
    public SourceNeed deriveSourceNeed(SourceClassification c, IntentKind intentKind) {
        if (intentKind == IntentKind.TOOL) {
            return SourceNeed.NONE;
        }
        boolean kb = c.knowledgeBase() || intentKind == IntentKind.KB;
        if (kb && c.sessionFile()) {
            return SourceNeed.MIXED;
        }
        if (kb) {
            return SourceNeed.KB;
        }
        // Explicit uploaded-file reference wins over currentness: a concrete file is
        // a stronger source signal than a temporal qualifier.
        if (c.sessionFile()) {
            return SourceNeed.FILE;
        }
        if (c.currentness()) {
            return SourceNeed.WEB;
        }
        return SourceNeed.NONE;
    }

    /**
     * Derive {@link TimeSensitivity} from a classification.
     */
    public TimeSensitivity deriveTimeSensitivity(SourceClassification c) {
        return c.currentness() ? TimeSensitivity.CURRENT : TimeSensitivity.UNKNOWN;
    }

    /**
     * Extract the most specific file reference in the text for query building.
     *
     * <p>Prefers a real filename with a supported extension; falls back to an
     * upload-noun phrase. Returns {@code null} if no file reference is found.</p>
     *
     * @return the file reference, or {@code null} if none detected
     */
    public String extractFileReference(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        // Prefer a real filename (most specific) regardless of where it appears.
        java.util.regex.Matcher fn = FILENAME_REFERENCE.matcher(text);
        if (fn.find()) {
            String name = fn.group(1);
            if (StringUtils.hasText(name)) {
                return name.trim();
            }
        }
        // Fall back to an upload-noun / determiner+noun phrase.
        java.util.regex.Matcher m = UPLOAD_NOUN_REFERENCE.matcher(text);
        if (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                String val = m.group(g);
                if (StringUtils.hasText(val)) {
                    return val.trim();
                }
            }
        }
        return null;
    }

    /**
     * Extract comparison operands (both sides) plus quoted/hyphenated objects.
     */
    private List<String> extractComparisonOperands(String text) {
        List<String> targets = new ArrayList<>();
        // English left operand ("A with B")
        addGroup(COMPARISON_OPERAND_LEFT.matcher(text), 1, targets);
        // English right operand ("with B")
        addGroup(COMPARISON_OPERAND_RIGHT.matcher(text), 1, targets);
        // Chinese both sides ("A 和 B")
        java.util.regex.Matcher cn = COMPARISON_OPERAND_CN.matcher(text);
        while (cn.find()) {
            addValue(cn.group(1), targets);
            addValue(cn.group(2), targets);
        }
        // Quoted / hyphenated identifiers
        QUOTED_OR_HYPHENATED.matcher(text).results().forEach(mr -> {
            for (int g = 1; g <= mr.groupCount(); g++) {
                addValue(mr.group(g), targets);
            }
        });
        return targets;
    }

    private void addGroup(java.util.regex.Matcher m, int group, List<String> into) {
        while (m.find()) {
            addValue(m.group(group), into);
        }
    }

    private void addValue(String val, List<String> into) {
        if (StringUtils.hasText(val) && !into.contains(val)) {
            into.add(val.trim());
        }
    }

    /**
     * Result of source-reference classification.
     *
     * @param sessionFile       whether the text references an uploaded/session file
     * @param knowledgeBase     whether the text references a knowledge base / policy
     * @param currentness       whether the text references current/latest/web content
     * @param comparison        whether the text expresses a comparison operation
     * @param comparisonTargets extracted comparison operands / object entities
     */
    public record SourceClassification(
            boolean sessionFile,
            boolean knowledgeBase,
            boolean currentness,
            boolean comparison,
            List<String> comparisonTargets
    ) {
        public SourceClassification {
            comparisonTargets = comparisonTargets == null ? List.of() : List.copyOf(comparisonTargets);
        }
    }

    /** Re-expose patterns for the preservation validator to reuse. */
    static Pattern sessionFilePattern() { return SESSION_FILE; }
    static Pattern knowledgeBasePattern() { return KNOWLEDGE_BASE; }
    static Pattern currentnessPattern() { return CURRENTNESS; }
    static Pattern comparisonPattern() { return COMPARISON; }
}
