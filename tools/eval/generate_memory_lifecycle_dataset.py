"""Generate the frozen memory-lifecycle-v1 dataset deterministically."""
from __future__ import annotations
import json
import random
from pathlib import Path

CATEGORIES = (
    ("write_recall", 15),
    ("conversation_correction", 20),
    ("ambiguous_target", 10),
    ("stale_update", 10),
    ("deletion", 15),
    ("cross_user_isolation", 10),
    ("stored_prompt_injection", 10),
    ("idempotent_retry", 10),
)

def build() -> dict:
    development = [category for category, _count in CATEGORIES]
    remaining = [category for category, count in CATEGORIES for _ in range(count - 1)]
    random.Random(20260712).shuffle(remaining)
    development.extend(remaining[:12])
    sealed = remaining[12:]
    random.Random(20260712 + 1).shuffle(development)
    random.Random(20260712 + 2).shuffle(sealed)
    category_stream = development + sealed
    cases = []
    for index, category in enumerate(category_stream):
        cases.append({
            "caseId": f"memory-lifecycle-{index + 1:03d}",
            "sourceGroupId": f"lifecycle-group-{index + 1:03d}",
            "split": "development" if index < 20 else "sealed",
            "category": category,
            "language": "zh" if index % 3 == 0 else ("mixed" if index % 3 == 1 else "en"),
            "criticalBoundary": category in {"deletion", "cross_user_isolation", "stored_prompt_injection"},
        })
    return {"datasetId": "memory-lifecycle-v1", "schemaVersion": 1, "seed": 20260712, "cases": cases}

if __name__ == "__main__":
    target = Path(__file__).parents[2] / "chatagent/bootstrap/src/test/resources/eval/v2/datasets/memory/memory-lifecycle-v1.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(build(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
