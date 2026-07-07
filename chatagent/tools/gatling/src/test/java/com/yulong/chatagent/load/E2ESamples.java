package com.yulong.chatagent.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Thread-safe collector for end-to-end turn-time samples (POST send → AI_DONE
 * on SSE for the same turnId).
 *
 * <p>Gatling's built-in assertions cannot cover arbitrary session-derived
 * percentiles, so samples are appended here per turn and the percentiles are
 * computed post-run from the collected data. Samples are also written
 * incrementally to a CSV so that data survives a Gatling ForkException that
 * kills the JVM before {@code after()} runs.</p>
 */
public final class E2ESamples {

    private static final ConcurrentLinkedQueue<Long> SAMPLES = new ConcurrentLinkedQueue<>();
    private static final Path LIVE_CSV = Path.of("tools", "gatling", "target", "gatling", "e2e-report", "e2e-samples-live.csv");

    private E2ESamples() {
    }

    /** Records one e2e turn time in nanoseconds. */
    public static void record(long durationNanos) {
        if (durationNanos > 0L) {
            SAMPLES.add(durationNanos);
            appendLiveCsv(durationNanos);
        }
    }

    /** Clears all samples and truncates the live CSV (called once per simulation JVM, before the run). */
    public static void reset() {
        SAMPLES.clear();
        try {
            Files.createDirectories(LIVE_CSV.getParent());
            Files.writeString(LIVE_CSV, "e2e_ms\n");
        } catch (IOException e) {
            throw new RuntimeException("Failed to reset e2e live CSV", e);
        }
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

    private static void appendLiveCsv(long durationNanos) {
        try {
            double ms = durationNanos / 1_000_000.0d;
            Files.writeString(LIVE_CSV, ms + "\n", StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Live CSV is best-effort; don't fail the turn if the disk write fails.
        }
    }
}
