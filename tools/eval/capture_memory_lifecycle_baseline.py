"""Capture pre-feature lifecycle capabilities from an immutable Git revision."""
from __future__ import annotations
import argparse, json, subprocess
from pathlib import Path

ABSENT = (
    "chatagent/bootstrap/src/main/java/com/yulong/chatagent/memory/application/MemoryApplicationService.java",
    "chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/tools/MemoryInspectTool.java",
    "chatagent/bootstrap/src/main/java/com/yulong/chatagent/agent/tools/MemoryCorrectTool.java",
    "chatagent/bootstrap/src/main/java/com/yulong/chatagent/memory/controller/MemoryController.java",
)

def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True, encoding="utf-8").strip()

def capture(revision: str) -> dict:
    resolved=git("rev-parse", revision)
    files=set(git("ls-tree","-r","--name-only",resolved).splitlines())
    unexpected=sorted(path for path in ABSENT if path in files)
    if unexpected:
        raise RuntimeError(f"baseline revision already contains lifecycle controls: {unexpected}")
    recall_path="chatagent/bootstrap/src/main/java/com/yulong/chatagent/memory/application/DefaultUserMemoryMilvusIndexService.java"
    recall_source=git("show",f"{resolved}:{recall_path}")
    if "USER_ID" not in recall_source or "active" not in recall_source:
        raise RuntimeError("baseline recall user/active filter evidence is missing")
    return {"baselineId":"memory-lifecycle-pre-feature-v1","revision":resolved,
            "supportedCategories":["write_recall","cross_user_isolation"],
            "evidence":{"absentPaths":list(ABSENT),"recallSource":recall_path,
                        "method":"git tree/source audit; candidate observations are executed separately"}}

if __name__ == "__main__":
    parser=argparse.ArgumentParser(); parser.add_argument("--revision",default="87f254d")
    parser.add_argument("--output",type=Path,required=True); args=parser.parse_args()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(capture(args.revision),indent=2)+"\n",encoding="utf-8")
