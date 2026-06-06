"""CLI for preparing the approved Phase 3 real-public evaluation datasets."""

from __future__ import annotations

import argparse
import os
from pathlib import Path

from chatagent_eval.datasets import write_json
from chatagent_eval.downloaders import beir_scifact, mtrag, sec_edgar
from chatagent_eval.downloaders.catalog import load_catalog


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-root", type=Path, default=Path("artifacts/eval/phase3"))
    parser.add_argument("--catalog-root", type=Path, required=True)
    parser.add_argument("--sources", nargs="+", choices=("beir-scifact", "mtrag-human", "sec-edgar-companyfacts"), default=("beir-scifact", "mtrag-human", "sec-edgar-companyfacts"))
    parser.add_argument("--sec-company-limit", type=int, default=25)
    parser.add_argument("--sec-user-agent", default=os.getenv("CHATAGENT_EVAL_SEC_USER_AGENT"))
    args = parser.parse_args()

    output_root = args.output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    results = []
    for source_id in args.sources:
        catalog = load_catalog(args.catalog_root, source_id)
        if source_id == "beir-scifact":
            results.append(beir_scifact.prepare(catalog, output_root))
        elif source_id == "mtrag-human":
            results.append(mtrag.prepare(catalog, output_root))
        else:
            if not args.sec_user_agent:
                raise ValueError("SEC download requires --sec-user-agent or CHATAGENT_EVAL_SEC_USER_AGENT")
            results.append(sec_edgar.prepare(catalog, output_root, user_agent=args.sec_user_agent, company_limit=args.sec_company_limit))
    write_json(output_root / "manifests" / "phase3-summary.json", {"sources": results})


if __name__ == "__main__":
    main()
