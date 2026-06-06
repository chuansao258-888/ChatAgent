"""Download official SEC company facts for real structured-text recall."""

from __future__ import annotations

import json
import re
import time
from html.parser import HTMLParser
from pathlib import Path
from typing import Any
from urllib.error import HTTPError

from chatagent_eval.datasets import (
    add_source_group_splits,
    build_dataset_manifest,
    build_split_manifest,
    build_source_manifest,
    validate_size_gate,
    write_json,
    write_jsonl,
)
from chatagent_eval.downloaders.common import download_file


def prepare(catalog: dict[str, Any], output_root: Path, *, user_agent: str, company_limit: int) -> dict[str, Any]:
    if "@" not in user_agent:
        raise ValueError("SEC User-Agent must include a contact email per SEC fair-access guidance")
    if company_limit < 1:
        raise ValueError("SEC company limit must be positive")
    raw_root = output_root / "raw" / catalog["sourceId"]
    tickers_path = _download_sec(catalog["sourceUrl"], raw_root / "company_tickers.json", user_agent)
    tickers = json.loads(tickers_path.read_text(encoding="utf-8"))
    candidates = [tickers[key] for key in sorted(tickers, key=lambda value: int(value))]

    source_files = [tickers_path]
    source_urls = {tickers_path: catalog["sourceUrl"]}
    rows: list[dict[str, Any]] = []
    for item in candidates:
        if len(rows) >= company_limit:
            break
        cik = f"{int(item['cik_str']):010d}"
        try:
            company_files, company_urls, row = _download_company(catalog, raw_root, output_root, user_agent, cik)
        except HTTPError as error:
            if error.code == 404:
                error.close()
                continue
            raise
        source_files.extend(company_files)
        source_urls.update(company_urls)
        rows.append(row)
    if len(rows) < company_limit:
        raise ValueError(f"SEC source supplied only {len(rows)} complete companies; required {company_limit}")

    rows = add_source_group_splits(rows)
    dataset_path = output_root / "datasets" / "text-recall" / "sec-companyfacts-text-recall-v1.jsonl"
    write_jsonl(dataset_path, rows)
    split_manifest_path = output_root / "manifests" / "splits" / "sec-companyfacts-text-recall-v1.json"
    write_json(split_manifest_path, build_split_manifest("sec-companyfacts-text-recall-v1", rows))
    source_manifest = build_source_manifest(
        source_id=catalog["sourceId"],
        source_url=catalog["sourceUrl"],
        source_revision=catalog["sourceRevision"],
        license_name=catalog["license"],
        license_url=catalog["licenseUrl"],
        output_root=output_root,
        local_path=raw_root,
        files=source_files,
        file_urls=source_urls,
        counts={"files": len(rows), "companies": len(rows), "downloadedFiles": len(source_files)},
        notes=catalog["notes"],
    )
    dataset_manifest = build_dataset_manifest(
        dataset_id="sec-companyfacts-text-recall-v1",
        version=1,
        source_ids=[catalog["sourceId"]],
        record_schema="eval-text-recall-dataset-record.schema.json",
        output_root=output_root,
        dataset_path=dataset_path,
        split_manifest_path=split_manifest_path,
        rows=rows,
    )
    write_json(output_root / "manifests" / "sources" / "sec-edgar-companyfacts.json", source_manifest)
    write_json(output_root / "manifests" / "datasets" / "sec-companyfacts-text-recall-v1.json", dataset_manifest)
    validate_size_gate("text-recall", "smoke", {"files": len(rows)})
    if company_limit >= 200:
        validate_size_gate("text-recall", "full", {"files": len(rows)})
    return {"source": source_manifest, "datasets": [dataset_manifest]}


def _download_company(
    catalog: dict[str, Any],
    raw_root: Path,
    output_root: Path,
    user_agent: str,
    cik: str,
) -> tuple[list[Path], dict[Path, str], dict[str, Any]]:
    company_facts_url = catalog["companyFactsUrlTemplate"].format(cik=cik)
    company_facts_path = raw_root / "companyfacts" / f"CIK{cik}.json"
    _download_sec(company_facts_url, company_facts_path, user_agent)

    submissions_url = catalog["submissionsUrlTemplate"].format(cik=cik)
    submissions_path = raw_root / "submissions" / f"CIK{cik}.json"
    _download_sec(submissions_url, submissions_path, user_agent)
    submissions = json.loads(submissions_path.read_text(encoding="utf-8"))
    filing = _select_html_filing(submissions)
    filing_url = catalog["filingUrlTemplate"].format(
        cik=int(cik),
        accession=filing["accessionNumber"].replace("-", ""),
        primaryDocument=filing["primaryDocument"],
    )
    filing_path = raw_root / "filings" / f"CIK{cik}-{filing['accessionNumber']}-{filing['primaryDocument']}"
    _download_sec(filing_url, filing_path, user_agent)
    return (
        [company_facts_path, submissions_path, filing_path],
        {
            company_facts_path: company_facts_url,
            submissions_path: submissions_url,
            filing_path: filing_url,
        },
        _text_recall_row(submissions, filing, cik, filing_url, filing_path, output_root),
    )


def _text_recall_row(
    submissions: dict[str, Any],
    filing: dict[str, str],
    cik: str,
    source_url: str,
    source_path: Path,
    output_root: Path,
) -> dict[str, Any]:
    phrases = grounded_html_phrases(source_path.read_text(encoding="utf-8", errors="replace"))
    return {
        "sampleId": f"sec-companyfacts-{cik}",
        "datasetId": "sec-companyfacts-text-recall-v1",
        "sourceGroupId": f"CIK{cik}",
        "sourceUrl": source_url,
        "sourceFile": source_path.resolve().relative_to(output_root.resolve()).as_posix(),
        "mediaType": "text/html",
        "requiredPhrases": phrases,
        "metadata": {
            "cik": cik,
            "entityName": submissions["name"],
            "accessionNumber": filing["accessionNumber"],
            "filingDate": filing["filingDate"],
            "form": filing["form"],
            "realPublicSource": True,
        },
    }


def grounded_html_phrases(html: str, limit: int = 8) -> list[str]:
    parser = _VisibleTextParser()
    parser.feed(html)
    phrases: list[str] = []
    for text in parser.text:
        normalized = re.sub(r"\s+", " ", text).strip()
        if 30 <= len(normalized) <= 180 and normalized not in phrases:
            phrases.append(normalized)
        if len(phrases) >= limit:
            break
    if len(phrases) < 2:
        raise ValueError("SEC filing HTML lacks enough grounded recall phrases")
    return phrases


def _download_sec(url: str, destination: Path, user_agent: str) -> Path:
    path = download_file(url, destination, user_agent=user_agent)
    time.sleep(0.12)
    return path


def _select_html_filing(submissions: dict[str, Any]) -> dict[str, str]:
    recent = submissions["filings"]["recent"]
    preferred_forms = {"10-K", "10-Q", "8-K"}
    for preferred_only in (True, False):
        for index, primary_document in enumerate(recent["primaryDocument"]):
            form = str(recent["form"][index])
            if str(primary_document).lower().endswith((".htm", ".html")) and (not preferred_only or form in preferred_forms):
                if Path(str(primary_document)).name != str(primary_document):
                    raise ValueError(f"SEC primary document contains an unsafe path: {primary_document}")
                return {
                    "accessionNumber": str(recent["accessionNumber"][index]),
                    "filingDate": str(recent["filingDate"][index]),
                    "form": form,
                    "primaryDocument": str(primary_document),
                }
    raise ValueError(f"SEC submissions contains no HTML filing for {submissions.get('name', 'unknown company')}")


class _VisibleTextParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.text: list[str] = []
        self._ignored_depth = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in {"script", "style", "ix:hidden"}:
            self._ignored_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style", "ix:hidden"} and self._ignored_depth:
            self._ignored_depth -= 1

    def handle_data(self, data: str) -> None:
        if not self._ignored_depth:
            self.text.append(data)
