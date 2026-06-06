from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import run_eval
from chatagent_eval.datasets import sha256_file
from chatagent_eval.schemas import load_json, validate
from chatagent_eval.text_recall_runner import TextRecallConfig, run_text_recall

ROOT = Path(__file__).resolve().parents[3]
RESOURCE_ROOT = ROOT / "chatagent" / "bootstrap" / "src" / "test" / "resources" / "eval" / "v2"


class TextRecallRunnerTest(unittest.TestCase):
    def test_runs_text_recall_and_writes_v2_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            dataset_root = _write_dataset_root(Path(temp_dir) / "phase3")
            run_dir = run_text_recall(
                dataset_root=dataset_root,
                output_root=Path(temp_dir) / "out",
                config=TextRecallConfig(run_id="text-recall-1", top_k=2),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            manifest = json.loads((run_dir / "manifest.json").read_text(encoding="utf-8"))
            samples = _read_jsonl(run_dir / "samples.jsonl")
            failures = _read_jsonl(run_dir / "failures.jsonl")
            validate(report, load_json(RESOURCE_ROOT / "schemas" / "eval-report.schema.json"))
            validate(manifest, load_json(RESOURCE_ROOT / "schemas" / "eval-run-manifest.schema.json"))
            validate(samples[0], load_json(RESOURCE_ROOT / "schemas" / "eval-sample.schema.json"))
            self.assertEqual("pass", report["status"])
            self.assertEqual(1.0, report["metrics"]["textRecall.parserPhraseRecall"])
            self.assertEqual(1.0, report["metrics"]["textRecall.chunkSpanRecall"])
            self.assertEqual(1.0, report["metrics"]["textRecall.retrievalContextPhraseRecall"])
            self.assertEqual(1.0, report["metrics"]["textRecall.citationSupportRecall"])
            self.assertEqual(1.0, report["metrics"]["textRecall.tableCellRecall"])
            self.assertEqual([], failures)
            self.assertEqual(2, manifest["config"]["topK"])

    def test_failure_report_includes_missing_phrases_and_actual_topk_contexts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            dataset_root = _write_dataset_root(Path(temp_dir) / "phase3", missing_phrase="Missing Evidence Phrase")
            run_dir = run_text_recall(
                dataset_root=dataset_root,
                output_root=Path(temp_dir) / "out",
                config=TextRecallConfig(run_id="text-recall-fail", top_k=1),
            )

            report = json.loads((run_dir / "report.json").read_text(encoding="utf-8"))
            failures = _read_jsonl(run_dir / "failures.jsonl")
            self.assertEqual("warn", report["status"])
            self.assertLess(report["metrics"]["textRecall.retrievalContextPhraseRecall"], 1.0)
            self.assertTrue(any("Missing Evidence Phrase" in failure["missingPhrases"] for failure in failures))
            self.assertIn("topKContexts", failures[0])
            self.assertEqual(1, len(failures[0]["topKContexts"]))

    def test_cli_text_recall_smoke_runs_without_provider(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            dataset_root = _write_dataset_root(temp_path / "phase3")
            with contextlib.redirect_stdout(io.StringIO()):
                exit_code = run_eval.main(
                    [
                        "text-recall-smoke",
                        "--dataset-root",
                        str(dataset_root),
                        "--output-root",
                        str(temp_path / "out"),
                        "--run-id",
                        "cli-text-recall",
                        "--top-k",
                        "2",
                    ]
                )

            report = json.loads((temp_path / "out" / "cli-text-recall" / "report.json").read_text(encoding="utf-8"))
            self.assertEqual(0, exit_code)
            self.assertEqual("pass", report["status"])
            self.assertEqual(2.0, report["metrics"]["textRecall.topK"])

    def test_rejects_source_file_path_escape(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            dataset_root = _write_dataset_root(Path(temp_dir) / "phase3", source_file="../outside.html")
            with self.assertRaisesRegex(ValueError, "escapes dataset root"):
                run_text_recall(
                    dataset_root=dataset_root,
                    output_root=Path(temp_dir) / "out",
                    config=TextRecallConfig(run_id="text-recall-escape"),
                )


def _write_dataset_root(path: Path, missing_phrase: str | None = None, source_file: str = "raw/sec/file.html") -> Path:
    source_path = path / source_file
    if ".." not in Path(source_file).parts:
        source_path.parent.mkdir(parents=True, exist_ok=True)
        source_path.write_text(
            """
            <html>
              <body>
                <p>Required Evidence Phrase appears in the public filing.</p>
                <table><tr><td>Revenue Table Cell 42</td></tr></table>
                <script>Required Evidence Phrase should not rely on scripts.</script>
              </body>
            </html>
            """,
            encoding="utf-8",
        )
    row = {
        "sampleId": "text-1",
        "datasetId": "sec-companyfacts-text-recall-v1",
        "sourceGroupId": "CIK0000000001",
        "split": "development",
        "sourceUrl": "https://www.sec.gov/Archives/example.html",
        "sourceFile": source_file,
        "mediaType": "text/html",
        "requiredPhrases": ["Required Evidence Phrase", missing_phrase or "public filing"],
        "metadata": {
            "entityName": "Example Corp",
            "form": "10-Q",
            "requiredTableCells": ["Revenue Table Cell 42"],
        },
    }
    dataset_path = path / "datasets" / "text-recall" / "sec-companyfacts-text-recall-v1.jsonl"
    dataset_path.parent.mkdir(parents=True, exist_ok=True)
    dataset_path.write_text(json.dumps(row) + "\n", encoding="utf-8")
    manifest = {
        "schemaVersion": 1,
        "datasetId": "sec-companyfacts-text-recall-v1",
        "version": 1,
        "sourceIds": ["sec-edgar-companyfacts"],
        "recordSchema": "eval-text-recall-dataset-record.schema.json",
        "localPath": "datasets/text-recall/sec-companyfacts-text-recall-v1.jsonl",
        "datasetHash": sha256_file(dataset_path),
        "splitManifestPath": "manifests/splits/sec-companyfacts-text-recall-v1.json",
        "splitManifestHash": "sha256:split",
        "recordCount": 1,
        "groupCount": 1,
        "splits": {"development": {"recordCount": 1, "groupCount": 1, "groupHash": "sha256:group"}},
    }
    manifest_path = path / "manifests" / "datasets" / "sec-companyfacts-text-recall-v1.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return path


def _read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


if __name__ == "__main__":
    unittest.main()
