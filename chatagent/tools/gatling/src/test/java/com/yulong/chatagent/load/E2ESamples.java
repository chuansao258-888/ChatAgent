package com.yulong.chatagent.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Thread-safe collector for end-to-end turn-time samples (POST send → AI_DONE
 * on SSE for the same turnId).
 *
 * <p>Gatling's built-in assertions cannot cover arbitrary session-derived
 * percentiles, so samples are appended here per turn and the percentiles are
 * computed post-run from the collected data.</p>
 */
public final class E2ESamples {

    private static final ConcurrentLinkedQueue<Long> SAMPLES = new ConcurrentLinkedQueue<>();

    private E2ESamples() {
    }

    /** Records one e2e turn time in nanoseconds. */
    public static void record(long durationNanos) {
        if (durationNanos > 0L) {
            SAMPLES.add(durationNanos);
        }
    }

    /** Clears all samples (called once per simulation JVM, before the run). */
    public static void reset() {
        SAMPLES.clear();
    }

    /** Returns the number of samples collected. */
    public static int count() {
        return SAMPLES.size();
    }

    /**
     * Computes the requested percentile in milliseconds.
     *
     * @param percentile 0-100
     * @return percentile value in ms, or -1 if no samples
     */
    public static double percentileMs(int percentile) {
        if (SAMPLES.isEmpty()) {
            return -1.0d;
        }
        java.util.List<Long> sorted = SAMPLES.stream().sorted().collect(Collectors.toList());
        int rank = (int) Math.ceil(percentile / 100.0d * sorted.size());
        int idx = Math.min(Math.max(rank - 1, 0), sorted.size() - 1);
        return sorted.get(idx) / 1_000_000.0d;
    }

    /**
     * Writes all samples (as milliseconds) to a CSV under the given report
     * directory for offline analysis.
     *
     * @param reportDir directory to write into (created if absent)
     * @return path to the written CSV
     */
    public static Path writeCsv(String reportDir) {
        try {
            Path dir = Paths.get(reportDir);
            Files.createDirectories(dir);
            Path file = dir.resolve("e2e-samples.csv");
            StringBuilder sb = new StringBuilder("e2e_ms\n");
            for (Long nanos : SAMPLES) {
                sb.append(nanos / 1_000_000.0d).append('\n');
            }
            Files.writeString(file, sb.toString());
            return file;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write e2e samples CSV", e);
        }
    }
}
