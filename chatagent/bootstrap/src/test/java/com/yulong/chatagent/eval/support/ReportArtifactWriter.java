package com.yulong.chatagent.eval.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes retained benchmark and reliability reports under target/eval-reports/.
 */
public final class ReportArtifactWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    private ReportArtifactWriter() {}

    public static Path writeReport(String reportName, Object report) {
        try {
            Path dir = ensureReportsDir();
            String timestamp = LocalDateTime.now().format(FORMATTER);
            Path file = dir.resolve(reportName + "-" + timestamp + ".json");
            MAPPER.writeValue(file.toFile(), report);
            return file;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write evaluation artifact: " + reportName, e);
        }
    }

    public static Path ensureReportsDir() throws IOException {
        Path dir = Path.of("target/eval-reports");
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }
}
