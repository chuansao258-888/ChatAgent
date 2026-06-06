from __future__ import annotations

import json
import re
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError

from chatagent_eval.datasets import (
    add_source_group_splits,
    build_source_manifest,
    connected_relevance_groups,
    read_jsonl,
    validate_no_split_leakage,
    validate_source_manifest,
    validate_size_gate,
)
from chatagent_eval.downloaders.catalog import load_catalog
from chatagent_eval.downloaders.common import safe_extract_zip
from chatagent_eval.downloaders import sec_edgar
from chatagent_eval.downloaders.sec_edgar import grounded_html_phrases
from chatagent_eval.schemas import load_json, validate

ROOT = Path(__file__).resolve().parents[3]
RESOURCE_ROOT = ROOT / "chatagent" / "bootstrap" / "src" / "test" / "resources" / "eval" / "v2"
CATALOG_ROOT = RESOURCE_ROOT / "corpora" / "catalog"


class Phase3DatasetTest(unittest.TestCase):
    def test_approved_source_catalogs_have_concrete_terms_and_validate(self) -> None:
        schema = load_json(RESOURCE_ROOT / "schemas" / "eval-source-catalog.schema.json")
        for source_id in ("beir-scifact", "mtrag-human", "sec-edgar-companyfacts"):
            with self.subTest(source_id=source_id):
                catalog = load_catalog(CATALOG_ROOT, source_id)
                validate(catalog, schema)
                self.assertTrue(catalog["licenseUrl"].startswith("https://"))

    def test_connected_relevance_groups_prevent_document_leakage(self) -> None:
        groups = connected_relevance_groups(
            {
                "q1": ["doc-a", "doc-b"],
                "q2": ["doc-b"],
                "q3": ["doc-c"],
            }
        )
        self.assertEqual(groups["q1"], groups["q2"])
        self.assertNotEqual(groups["q1"], groups["q3"])
        rows = add_source_group_splits(
            [
                {"sampleId": query_id, "sourceGroupId": group_id}
                for query_id, group_id in groups.items()
            ]
        )
        validate_no_split_leakage(rows)
        self.assertEqual(rows[0]["split"], rows[1]["split"])

    def test_split_leakage_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "appears in both"):
            validate_no_split_leakage(
                [
                    {"sourceGroupId": "conversation-1", "split": "development"},
                    {"sourceGroupId": "conversation-1", "split": "holdout"},
                ]
            )

    def test_source_manifest_rejects_placeholder_license_and_private_path(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output_root = Path(temp_dir)
            source_file = output_root / "raw" / "source.json"
            source_file.parent.mkdir(parents=True)
            source_file.write_text("{}", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "concrete license"):
                build_source_manifest(
                    source_id="source",
                    source_url="https://example.com/source",
                    source_revision="revision",
                    license_name="unknown",
                    license_url="https://example.com/license",
                    output_root=output_root,
                    local_path=source_file.parent,
                    files=[source_file],
                    counts={"files": 1},
                    notes="test",
                )
            with self.assertRaisesRegex(ValueError, "outside output root"):
                build_source_manifest(
                    source_id="source",
                    source_url="https://example.com/source",
                    source_revision="revision",
                    license_name="Apache-2.0",
                    license_url="https://example.com/license",
                    output_root=output_root / "different-root",
                    local_path=source_file.parent,
                    files=[source_file],
                    counts={"files": 1},
                    notes="test",
                )

    def test_safe_zip_extraction_rejects_path_escape(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            archive = root / "unsafe.zip"
            with zipfile.ZipFile(archive, "w") as target:
                target.writestr("../outside.txt", "no")
            with self.assertRaisesRegex(ValueError, "escapes extraction root"):
                safe_extract_zip(archive, root / "extract")

    def test_size_gates_cover_real_full_suite_targets(self) -> None:
        validate_size_gate("retrieval", "full", {"queries": 1109, "documents": 5183})
        validate_size_gate("memory", "full", {"tasks": 842})
        validate_size_gate("multiturn", "full", {"conversations": 110})
        validate_size_gate("text-recall", "full", {"files": 200})
        with self.assertRaisesRegex(ValueError, "files=25 < 200"):
            validate_size_gate("text-recall", "full", {"files": 25})

    def test_sec_downloader_requires_contact_bearing_user_agent(self) -> None:
        catalog = load_catalog(CATALOG_ROOT, "sec-edgar-companyfacts")
        with tempfile.TemporaryDirectory() as temp_dir:
            with self.assertRaisesRegex(ValueError, "contact email"):
                sec_edgar.prepare(catalog, Path(temp_dir), user_agent="ChatAgent-eval/1.0", company_limit=25)

    def test_sec_downloader_skips_missing_company_facts_until_target_is_met(self) -> None:
        catalog = load_catalog(CATALOG_ROOT, "sec-edgar-companyfacts")
        with tempfile.TemporaryDirectory() as temp_dir:
            output_root = Path(temp_dir)
            tickers = {str(index): {"cik_str": index + 1} for index in range(26)}

            def fake_download_sec(url: str, destination: Path, user_agent: str) -> Path:
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text(json.dumps(tickers), encoding="utf-8")
                return destination

            attempts = 0

            def fake_download_company(*args: object, **kwargs: object) -> tuple[list[Path], dict[Path, str], dict]:
                nonlocal attempts
                attempts += 1
                if attempts == 1:
                    raise HTTPError("https://data.sec.gov/missing", 404, "Not Found", {}, None)
                cik = f"{attempts:010d}"
                source_file = output_root / "raw" / "sec-edgar-companyfacts" / f"{cik}.htm"
                source_file.parent.mkdir(parents=True, exist_ok=True)
                source_file.write_text("<html>real filing text</html>", encoding="utf-8")
                return (
                    [source_file],
                    {source_file: f"https://www.sec.gov/{cik}.htm"},
                    {
                        "sampleId": f"sec-{cik}",
                        "datasetId": "sec-companyfacts-text-recall-v1",
                        "sourceGroupId": f"CIK{cik}",
                    },
                )

            with patch.object(sec_edgar, "_download_sec", side_effect=fake_download_sec):
                with patch.object(sec_edgar, "_download_company", side_effect=fake_download_company):
                    result = sec_edgar.prepare(
                        catalog,
                        output_root,
                        user_agent="ChatAgent Evaluation contact@example.com",
                        company_limit=25,
                    )
            self.assertEqual(26, attempts)
            self.assertEqual(25, result["source"]["counts"]["companies"])

    def test_sec_recall_phrases_are_extracted_from_real_file_text(self) -> None:
        html = """
        <html><head><style>ignore this sufficiently long style content forever</style></head>
        <body>
          <ix:hidden>ignore this sufficiently long hidden XBRL content forever</ix:hidden>
          <h1>Example Corporation Annual Report for the fiscal year ended September 30, 2025</h1>
          <p>Net sales increased because customer demand remained strong across the reporting period.</p>
        </body></html>
        """
        phrases = grounded_html_phrases(html)
        self.assertEqual(2, len(phrases))
        self.assertTrue(all(phrase in html for phrase in phrases))
        self.assertTrue(all("ignore this" not in phrase for phrase in phrases))

    def test_committed_phase3_resources_do_not_reference_private_absolute_paths(self) -> None:
        private_path = re.compile(r"(?:[A-Za-z]:\\Users\\|/Users/|/home/)")
        for path in sorted((RESOURCE_ROOT / "corpora").rglob("*")) + sorted((RESOURCE_ROOT / "datasets").rglob("*")):
            if path.is_file():
                with self.subTest(path=path.name):
                    self.assertIsNone(private_path.search(path.read_text(encoding="utf-8")))

    def test_generated_phase3_manifests_validate_when_present(self) -> None:
        output_root = ROOT / "artifacts" / "eval" / "phase3"
        if not output_root.exists():
            self.skipTest("run prepare_phase3 to validate downloaded manifests")
        schema_pairs = (
            ("sources", "eval-corpus-manifest.schema.json"),
            ("datasets", "eval-dataset-manifest.schema.json"),
            ("splits", "eval-split-manifest.schema.json"),
        )
        for directory, schema_name in schema_pairs:
            schema = load_json(RESOURCE_ROOT / "schemas" / schema_name)
            for path in sorted((output_root / "manifests" / directory).glob("*.json")):
                with self.subTest(path=path.name):
                    validate(json.loads(path.read_text(encoding="utf-8")), schema)
        for path in sorted((output_root / "manifests" / "sources").glob("*.json")):
            validate_source_manifest(json.loads(path.read_text(encoding="utf-8")))
        for manifest_path in sorted((output_root / "manifests" / "datasets").glob("*.json")):
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            record_schema = load_json(RESOURCE_ROOT / "schemas" / manifest["recordSchema"])
            rows = read_jsonl(output_root / manifest["localPath"])
            with self.subTest(dataset=manifest["datasetId"]):
                validate_no_split_leakage(rows)
                for row in rows:
                    validate(row, record_schema)
        sources = {
            path.stem: json.loads(path.read_text(encoding="utf-8"))
            for path in (output_root / "manifests" / "sources").glob("*.json")
        }
        if "beir-scifact" in sources:
            validate_size_gate("retrieval", "full", sources["beir-scifact"]["counts"])
        if "mtrag-human" in sources:
            validate_size_gate("memory", "full", {"tasks": sources["mtrag-human"]["counts"]["queries"]})
            validate_size_gate("multiturn", "full", sources["mtrag-human"]["counts"])


if __name__ == "__main__":
    unittest.main()
