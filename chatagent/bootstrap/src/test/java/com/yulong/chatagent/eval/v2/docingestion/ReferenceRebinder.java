package com.yulong.chatagent.eval.v2.docingestion;

import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic reference rebinder for Phase 10d-B3.2.
 *
 * <p>Maps old dataset references (from the baseline 200-source run) to new chunks
 * produced after the HTML chunking fix. The algorithm uses exact semantic-text
 * containment first, then a high-coverage fallback only for HTML-backed sources,
 * to produce exactly one new reference per row.</p>
 *
 * <p>Fail-closed: rows below the allowed matching threshold are never silently dropped.
 * Natural duplicate evidence is resolved deterministically and recorded in the receipt.</p>
 */
class ReferenceRebinder {

    private static final double HTML_FALLBACK_MIN_COVERAGE = 0.95;
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^\\r\\n]*?\\)");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\([^\\r\\n]*?\\)");
    private static final Pattern SYNTHETIC_TABLE_HEADER = Pattern.compile(
            "(?im)^\\s*\\|(?:\\s*Column\\s+\\d+\\s*\\|)+\\s*$");

    private ReferenceRebinder() {
        // static utility — not instantiated
    }

    // -----------------------------------------------------------------------
    // Input / output records
    // -----------------------------------------------------------------------

    /**
     * Narrow input row — excludes query/retrieval/scoring fields that must not
     * influence rebinding decisions.
     */
    record RebindInput(
            String sampleId,
            String sourceUrl,
            String sourceSha256,
            String filename,
            String format,
            String split,
            String oldReferenceChunkId,
            String referenceContent) {
    }

    /**
     * Single chunk in the new inventory, sorted by source identity then chunkIndex.
     */
    record NewChunk(
            String sourceUrl,
            String sourceSha256,
            String filename,
            String chunkId,
            int chunkIndex,
            String content) {
    }

    /**
     * Result of rebinding one input row.
     *
     * @param status             "bound" or "missing"
     * @param newReferenceChunkId the selected primary chunk (null unless "bound")
     * @param auditWindowChunkIds the shortest contiguous window (empty unless "bound")
     * @param windowLength       number of chunks in the window (0 unless "bound")
     * @param matchMethod        exact, html-token-coverage, or none
     * @param matchCoverage      normalized token-multiset coverage of the audit window
     * @param tieBreak           deterministic tie-break decision, or none
     */
    record RebindOutput(
            String sampleId,
            String oldReferenceChunkId,
            String newReferenceChunkId,
            List<String> auditWindowChunkIds,
            int windowLength,
            String status,
            String sourceIdentity,
            String matchMethod,
            double matchCoverage,
            String tieBreak) {
    }

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    /**
     * Rebind all input rows against the new chunk inventory.
     *
     * <p>The inventory must be pre-sorted by (sourceUrl, sourceSha256, filename, chunkIndex)
     * for deterministic output. Results are sorted by sampleId.</p>
     *
     * @param inputs   narrow input rows (must not carry query/retrieval/scoring fields)
     * @param inventory new chunks from re-ingestion
     * @return list of outputs sorted by sampleId
     */
    static List<RebindOutput> rebind(List<RebindInput> inputs, List<NewChunk> inventory) {
        // Group inventory by stable source identity.
        Map<String, List<NewChunk>> bySource = inventory.stream()
                .collect(Collectors.groupingBy(
                        ReferenceRebinder::sourceIdentity,
                        // Preserve encounter order within each group.
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<RebindOutput> outputs = new ArrayList<>();
        for (RebindInput input : inputs) {
            outputs.add(rebindOne(input, bySource));
        }

        // Deterministic ordering: sort by sampleId.
        outputs.sort(Comparator.comparing(RebindOutput::sampleId));
        return outputs;
    }

    // -----------------------------------------------------------------------
    // Per-row algorithm
    // -----------------------------------------------------------------------

    private static RebindOutput rebindOne(RebindInput input, Map<String, List<NewChunk>> bySource) {
        String identity = sourceIdentity(input.sourceUrl(), input.sourceSha256(), input.filename());
        List<NewChunk> sourceChunks = bySource.get(identity);

        if (sourceChunks == null || sourceChunks.isEmpty()) {
            return new RebindOutput(input.sampleId(), input.oldReferenceChunkId(),
                    null, List.of(), 0, "missing", identity, "none", 0.0, "none");
        }

        String normalizedOld = normalizedMatchText(input.referenceContent());
        if (normalizedOld.isEmpty()) {
            return new RebindOutput(input.sampleId(), input.oldReferenceChunkId(),
                    null, List.of(), 0, "missing", identity, "none", 0.0, "none");
        }

        WindowResult window = findShortestExactWindow(sourceChunks, normalizedOld);
        if (window == null && htmlFallbackAllowed(input)) {
            window = findShortestCoverageWindow(sourceChunks, normalizedOld);
        }
        if (window == null) {
            return new RebindOutput(input.sampleId(), input.oldReferenceChunkId(),
                    null, List.of(), 0, "missing", identity, "none", 0.0, "none");
        }

        PrimaryResult primary = selectPrimaryChunk(window.chunks, normalizedOld);
        String tieBreak = combineTieBreaks(window.tieBreak, primary.tieBreak ? "earliest-primary" : "none");

        return new RebindOutput(input.sampleId(), input.oldReferenceChunkId(),
                primary.chunk.chunkId(),
                window.chunks.stream().map(NewChunk::chunkId).toList(),
                window.chunks.size(), "bound", identity, window.matchMethod, window.coverage, tieBreak);
    }

    // -----------------------------------------------------------------------
    // Shortest-window search
    // -----------------------------------------------------------------------

    private static WindowResult findShortestExactWindow(List<NewChunk> chunks, String normalizedOld) {
        int n = chunks.size();
        List<WindowCandidate> candidates = new ArrayList<>();
        int bestLen = Integer.MAX_VALUE;
        Map<String, Integer> oldTokens = tokenFrequencies(normalizedOld);

        for (int start = 0; start < n; start++) {
            StringBuilder concat = new StringBuilder();
            for (int end = start; end < n && (end - start + 1) <= bestLen; end++) {
                if (end > start) {
                    concat.append(' ');
                }
                String normalizedChunk = normalizedMatchText(chunks.get(end).content());
                concat.append(normalizedChunk);

                if (concat.toString().contains(normalizedOld)) {
                    int len = end - start + 1;
                    double coverage = tokenMultisetCoverage(oldTokens, tokenFrequencies(concat.toString()));
                    if (len < bestLen) {
                        bestLen = len;
                        candidates.clear();
                        candidates.add(new WindowCandidate(start, chunks.subList(start, end + 1), coverage));
                    } else if (len == bestLen) {
                        candidates.add(new WindowCandidate(start, chunks.subList(start, end + 1), coverage));
                    }
                    break; // no need to extend further for this start
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        return selectWindow(candidates, "exact");
    }

    private static WindowResult findShortestCoverageWindow(List<NewChunk> chunks, String normalizedOld) {
        Map<String, Integer> oldTokens = tokenFrequencies(normalizedOld);
        List<WindowCandidate> candidates = new ArrayList<>();
        int bestLen = Integer.MAX_VALUE;

        for (int start = 0; start < chunks.size(); start++) {
            Map<String, Integer> windowTokens = new HashMap<>();
            for (int end = start; end < chunks.size() && (end - start + 1) <= bestLen; end++) {
                mergeTokenFrequencies(windowTokens, tokenFrequencies(normalizedMatchText(chunks.get(end).content())));
                double coverage = tokenMultisetCoverage(oldTokens, windowTokens);
                if (coverage >= HTML_FALLBACK_MIN_COVERAGE) {
                    int len = end - start + 1;
                    if (len < bestLen) {
                        bestLen = len;
                        candidates.clear();
                    }
                    if (len == bestLen) {
                        candidates.add(new WindowCandidate(start, chunks.subList(start, end + 1), coverage));
                    }
                    break;
                }
            }
        }
        return candidates.isEmpty() ? null : selectWindow(candidates, "html-token-coverage");
    }

    private static WindowResult selectWindow(List<WindowCandidate> candidates, String matchMethod) {
        double bestCoverage = candidates.stream()
                .mapToDouble(WindowCandidate::coverage)
                .max()
                .orElse(0.0);
        List<WindowCandidate> best = candidates.stream()
                .filter(candidate -> Double.compare(candidate.coverage, bestCoverage) == 0)
                .sorted(Comparator.comparingInt(WindowCandidate::start))
                .toList();
        String tieBreak;
        if (candidates.size() == 1) {
            tieBreak = "none";
        } else if (best.size() == 1) {
            tieBreak = "highest-coverage-window";
        } else {
            tieBreak = "earliest-window";
        }
        WindowCandidate selected = best.get(0);
        return new WindowResult(selected.chunks, matchMethod, selected.coverage, tieBreak);
    }

    private static boolean htmlFallbackAllowed(RebindInput input) {
        String filename = input.filename() == null ? "" : input.filename().toLowerCase(Locale.ROOT);
        return ("SEC_HTML".equals(input.format()) || "WEB_MD".equals(input.format()))
                && (filename.endsWith(".html") || filename.endsWith(".htm"));
    }

    private static void mergeTokenFrequencies(Map<String, Integer> target, Map<String, Integer> addition) {
        addition.forEach((token, count) -> target.merge(token, count, Integer::sum));
    }

    private static String combineTieBreaks(String windowTieBreak, String primaryTieBreak) {
        if ("none".equals(windowTieBreak)) {
            return primaryTieBreak;
        }
        if ("none".equals(primaryTieBreak)) {
            return windowTieBreak;
        }
        return windowTieBreak + "," + primaryTieBreak;
    }

    private record WindowCandidate(int start, List<NewChunk> chunks, double coverage) {
    }

    private record WindowResult(List<NewChunk> chunks, String matchMethod, double coverage, String tieBreak) {
    }

    // -----------------------------------------------------------------------
    // Primary-chunk selection: max token-multiset coverage
    // -----------------------------------------------------------------------

    private static PrimaryResult selectPrimaryChunk(List<NewChunk> window, String normalizedOld) {
        Map<String, Integer> oldTokens = tokenFrequencies(normalizedOld);
        if (oldTokens.isEmpty()) {
            return new PrimaryResult(window.get(0), 0.0, false);
        }

        NewChunk best = null;
        double bestCoverage = -1.0;
        boolean tieBreak = false;

        for (NewChunk chunk : window) {
            Map<String, Integer> chunkTokens = tokenFrequencies(normalizedMatchText(chunk.content()));
            double coverage = tokenMultisetCoverage(oldTokens, chunkTokens);

            if (coverage > bestCoverage) {
                bestCoverage = coverage;
                best = chunk;
                tieBreak = false;
            } else if (Double.compare(coverage, bestCoverage) == 0) {
                tieBreak = true;
            }
        }
        return new PrimaryResult(best, bestCoverage, tieBreak);
    }

    private record PrimaryResult(NewChunk chunk, double coverage, boolean tieBreak) {
    }

    /**
     * Computes token-multiset coverage: sum(min(oldCount, chunkCount)) / sum(oldCount).
     */
    static double tokenMultisetCoverage(Map<String, Integer> oldTokens, Map<String, Integer> chunkTokens) {
        int oldTotal = oldTokens.values().stream().mapToInt(Integer::intValue).sum();
        if (oldTotal == 0) {
            return 0.0;
        }
        int overlap = 0;
        for (Map.Entry<String, Integer> entry : oldTokens.entrySet()) {
            int chunkCount = chunkTokens.getOrDefault(entry.getKey(), 0);
            overlap += Math.min(entry.getValue(), chunkCount);
        }
        return (double) overlap / oldTotal;
    }

    /**
     * Tokenizes normalized text into lowercase alphanumeric words, counting frequencies.
     */
    static Map<String, Integer> tokenFrequencies(String normalizedText) {
        Map<String, Integer> freq = new HashMap<>();
        if (normalizedText == null || normalizedText.isEmpty()) {
            return freq;
        }
        String[] tokens = normalizedText.split("\\s+");
        for (String token : tokens) {
            if (!token.isEmpty()) {
                freq.merge(token, 1, Integer::sum);
            }
        }
        return freq;
    }

    // -----------------------------------------------------------------------
    // Identity and normalization utilities
    // -----------------------------------------------------------------------

    static String sourceIdentity(String sourceUrl, String sourceSha256, String filename) {
        return sourceUrl + "|" + sourceSha256 + "|" + filename;
    }

    static String sourceIdentity(RebindInput input) {
        return sourceIdentity(input.sourceUrl(), input.sourceSha256(), input.filename());
    }

    static String sourceIdentity(NewChunk chunk) {
        return sourceIdentity(chunk.sourceUrl(), chunk.sourceSha256(), chunk.filename());
    }

    /**
     * Normalizes semantic source text for rebinding. Legacy raw HTML and renderer-only
     * Markdown syntax are removed before lowercase alphanumeric normalization.
     */
    static String normalizedMatchText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String semantic = Parser.unescapeEntities(value, false);
        semantic = HTML_TAG.matcher(semantic).replaceAll(" ");
        semantic = MARKDOWN_IMAGE.matcher(semantic).replaceAll("$1");
        semantic = MARKDOWN_LINK.matcher(semantic).replaceAll("$1");
        semantic = SYNTHETIC_TABLE_HEADER.matcher(semantic).replaceAll(" ");
        return semantic.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
