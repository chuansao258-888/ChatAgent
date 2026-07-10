package com.yulong.chatagent.agent.runtime.contract;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts salient content tokens from a user query so the preservation gate
 * can catch object/entity substitution in ordinary (non-comparison) questions.
 *
 * <p>This closes the Phase 2 P1 gap where a rewrite could silently replace the
 * core object of a normal KB question ("annual leave policy" → "sick leave
 * policy") because only source/time/comparison words were checked. The extractor
 * pulls:</p>
 * <ul>
 *   <li>English content words (length &ge; 3, stopword-filtered) — the substantive
 *       nouns/adjectives/verbs that identify the object, including short objects like
 *       tax/law/pay;</li>
 *   <li>Chinese 2-grams (conservative, no dictionary) — captures object nouns
 *       like 年假/报销 even without a segmenter;</li>
 *   <li>numbers, version codes, and alphanumeric identifiers (e.g. v2, INC-001);</li>
 *   <li>filenames with extensions.</li>
 * </ul>
 *
 * <p>The extractor is deliberately conservative: it over-extracts rather than
 * misses, because a false-negative (missed object) lets a lossy rewrite through,
 * while a false-positive (extra token) only forces a safe fallback to the
 * original text.</p>
 */
@Component
public class QueryContentTokenExtractor {

    /** Common English stopwords + light question/function words to exclude. */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "this", "that", "these", "those", "my", "our", "your",
            "is", "are", "was", "were", "be", "been", "being", "do", "does", "did",
            "what", "which", "who", "whom", "whose", "where", "when", "why", "how",
            "can", "could", "would", "should", "shall", "will", "may", "might", "must",
            "of", "for", "to", "in", "on", "at", "by", "with", "from", "about",
            "and", "or", "but", "if", "then", "than", "so", "as", "into", "over",
            "it", "its", "their", "there", "here", "they", "them", "you", "we", "us",
            "have", "has", "had", "get", "got", "make", "made", "want", "need",
            "tell", "show", "give", "let", "please", "just", "also", "like",
            "any", "all", "some", "more", "most", "such", "same", "other", "every",
            "now", "one", "two", "very", "much", "many", "few",
            // conversational fillers / greetings that are not object content
            "hello", "thanks", "thank", "okay", "fine",
            // operation verbs are intent, normalized into QueryOperation, not object content
            "summarize", "summary", "extract", "verify", "check",
            "compare", "comparison", "versus", "contrast", "difference", "differ",
            "find", "search", "look", "query", "ask", "answer", "explain", "describe"
    );

    /** English content word: length >= 3, letters/digits, not a stopword. */
    private static final Pattern ENGLISH_CONTENT_WORD = Pattern.compile("\\b[A-Za-z][A-Za-z0-9]{2,}\\b");

    /**
     * Numbers and version/code identifiers: 123, v2, 3.5, INC-001, 2024-Q3.
     * Specific patterns (quarter/date, codes) are listed BEFORE the generic number
     * so that a full token like "2024-Q3" is captured as one value, not split into
     * "2024" by the generic alternative. This makes replacing a component
     * (2024-Q3 → 2024-Q4) change the whole matched value and be detected.
     */
    private static final Pattern NUMBER_OR_CODE = Pattern.compile(
            "\\b\\d{4}[-/][A-Za-z0-9]{1,3}(?:[-/]\\d{1,2})?\\b" // 2024-Q3, 2024/01, 2024-Q3/15
                    + "|\\b[A-Z]{2,}[-_]?[0-9A-Z]+\\b"            // INC-001, API2
                    + "|\\b[A-Za-z]{2,}[-_][0-9A-Za-z]+\\b"       // policy-A, item_3, PIP-2024
                    + "|\\b[vV]\\d+(?:[.]\\d+)*\\b"               // v2, V3.1
                    + "|\\b\\d+(?:[.]\\d+)*\\b"                    // 123, 3.5, 3.5.1 (last: most generic)
    );

    /** Filename with a supported extension. */
    private static final Pattern FILENAME = Pattern.compile(
            "\\b[\\w.\\-]+\\." + SupportedFileExtensions.EXTENSION_GROUP + "\\b"
    );

    /** CJK run (for 2-gram extraction). */
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]{2,}");

    /**
     * Chinese comparison operator/function characters to strip before 2-gramming,
     * so comparison verbs (比较/对比) and connectors (和/与/跟) do not pollute the
     * object tokens. These are operation/intent, not object content.
     */
    private static final String CN_OPERATOR_CHARS = "比较对比和与跟的么怎吗呢吧啊哦";

    /**
     * Extract salient content tokens from the query text.
     *
     * @param text the user query
     * @return distinct, lower-cased content tokens (may be empty)
     */
    public List<String> extract(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        // English content words (stopword-filtered)
        ENGLISH_CONTENT_WORD.matcher(text).results().forEach(m -> {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (!STOPWORDS.contains(w)) {
                addUnique(w, tokens);
            }
        });
        // Numbers / codes / version identifiers
        NUMBER_OR_CODE.matcher(text).results().forEach(m -> addUnique(m.group().toLowerCase(Locale.ROOT), tokens));
        // Filenames
        FILENAME.matcher(text).results().forEach(m -> addUnique(m.group().toLowerCase(Locale.ROOT), tokens));
        // Chinese 2-grams (sliding window over each CJK run). Strip comparison
        // operator/connector characters first so they do not form spurious 2-grams
        // that would force fallback on legitimate synonym rewrites.
        String cjkStripped = stripCjkOperators(text);
        CJK_RUN.matcher(cjkStripped).results().forEach(m -> {
            String run = m.group();
            for (int i = 0; i + 2 <= run.length(); i++) {
                addUnique(run.substring(i, i + 2), tokens);
            }
        });
        return tokens;
    }

    private String stripCjkOperators(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (CN_OPERATOR_CHARS.indexOf(ch) < 0) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private void addUnique(String token, List<String> into) {
        if (StringUtils.hasText(token) && !into.contains(token)) {
            into.add(token);
        }
    }
}
