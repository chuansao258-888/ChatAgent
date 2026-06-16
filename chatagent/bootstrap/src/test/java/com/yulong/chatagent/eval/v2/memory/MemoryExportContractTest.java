package com.yulong.chatagent.eval.v2.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MemoryExportContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void fullExportFiltersSplitsBeforeApplyingSampleLimit() throws Exception {
        List<JsonNode> rows = List.of(
                row("holdout", "h1"),
                row("calibration", "c1"),
                row("development", "d1"),
                row("holdout", "h2"),
                row("calibration", "c2")
        );

        List<JsonNode> selected = MemoryExportEvalTest.selectFullExportRows(
                rows, 2, Set.of("calibration", "development"));

        assertThat(selected).extracting(row -> row.path("sampleId").asText())
                .containsExactly("c1", "d1");
        assertThat(selected).allMatch(row -> !"holdout".equals(row.path("split").asText()));
    }

    @Test
    void splitAndRunIdConfigurationFailClosed() {
        assertThat(MemoryExportEvalTest.parseSplits("calibration, development"))
                .containsExactly("calibration", "development");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MemoryExportEvalTest.parseSplits("calibration,unknown"));
        assertThat(MemoryExportEvalTest.validatedRunId("memory-candidate-v2")).isEqualTo("memory-candidate-v2");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MemoryExportEvalTest.validatedRunId("../overwrite"));
    }

    @Test
    void l3OnlySelectionIsExactOrderedAndRejectsHoldout() throws Exception {
        List<JsonNode> rows = List.of(
                row("calibration", "c1"),
                row("development", "d1"),
                row("holdout", "h1")
        );

        assertThat(MemoryExportEvalTest.selectExactL3OnlyRows(rows, List.of("d1", "c1")))
                .extracting(row -> row.path("sampleId").asText())
                .containsExactly("d1", "c1");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MemoryExportEvalTest.selectExactL3OnlyRows(rows, List.of("h1")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MemoryExportEvalTest.selectExactL3OnlyRows(rows, List.of("missing")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MemoryExportEvalTest.selectExactL3OnlyRows(rows, List.of("c1", "c1")));
    }

    @Test
    void l3OnlyReconstructsAlternatingAtomicTurns() throws Exception {
        JsonNode row = OBJECT_MAPPER.readTree("""
                {"turns":[
                  {"speaker":"user","text":"I own a business."},
                  {"speaker":"agent","text":"Understood."},
                  {"speaker":"user","text":"I prefer email."}
                ]}
                """);

        var turns = MemoryExportEvalTest.toAtomicTurns(row);

        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).userMessages()).containsExactly("I own a business.");
        assertThat(turns.get(0).assistantConclusion()).isEqualTo("Understood.");
        assertThat(turns.get(1).userMessages()).containsExactly("I prefer email.");
        assertThat(turns.get(1).assistantConclusion()).isNull();
    }

    @Test
    void repositoryPathResolutionRejectsEscape() {
        Path repositoryRoot = Path.of("").toAbsolutePath().normalize();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> MemoryExportEvalTest.resolveRepositoryPath(repositoryRoot, "../outside.json"));
    }

    private JsonNode row(String split, String sampleId) throws Exception {
        return OBJECT_MAPPER.readTree(
                "{\"split\":\"" + split + "\",\"sampleId\":\"" + sampleId + "\"}");
    }
}
