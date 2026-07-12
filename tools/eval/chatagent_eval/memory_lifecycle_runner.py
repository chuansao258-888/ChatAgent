"""Score paired memory lifecycle observations without invoking another judge."""
from __future__ import annotations
import hashlib, json, math, random
from pathlib import Path

def _read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))

def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def _percentile(values: list[float], q: float) -> float:
    ordered=sorted(values); return ordered[min(len(ordered)-1, math.floor(q*len(ordered)))]

def _bootstrap(diffs: list[int], seed: int, samples: int) -> list[float]:
    rng=random.Random(seed); n=len(diffs)
    estimates=sorted(sum(diffs[rng.randrange(n)] for _ in range(n))/n for _ in range(samples))
    return [100*estimates[int(.025*samples)], 100*estimates[int(.975*samples)-1]]

def run(dataset_path: Path, baseline_path: Path, candidate_path: Path, output_path: Path) -> dict:
    dataset=_read(dataset_path); baseline=_read(baseline_path); candidate=_read(candidate_path)
    cases={case["caseId"]:case for case in dataset["cases"]}
    observations={row["caseId"]:row for row in candidate["observations"]}
    if len(cases)!=100 or len(observations)!=100 or set(cases)!=set(observations):
        raise ValueError("dataset and candidate observations must contain the same 100 unique cases")
    development=[case for case in dataset["cases"] if case["split"]=="development"]
    sealed=[case for case in dataset["cases"] if case["split"]=="sealed"]
    groups=[case["sourceGroupId"] for case in dataset["cases"]]
    required={"conversation_correction","ambiguous_target","stale_update","deletion","cross_user_isolation","stored_prompt_injection"}
    if len(development)!=20 or len(sealed)!=80 or len(groups)!=len(set(groups)):
        raise ValueError("dataset requires 20 development, 80 sealed, and unique source groups")
    if not required <= {case["category"] for case in development} or not required <= {case["category"] for case in sealed}:
        raise ValueError("both splits must cover every required lifecycle boundary")
    supported=set(baseline["supportedCategories"])
    baseline_values=[case["category"] in supported for case in sealed]
    candidate_values=[bool(observations[case["caseId"]]["candidatePassed"]) for case in sealed]
    diffs=[int(c)-int(b) for b,c in zip(baseline_values,candidate_values)]
    categories=sorted({case["category"] for case in sealed})
    breakdown={}
    for category in categories:
        ids=[case["caseId"] for case in sealed if case["category"]==category]
        breakdown[category]={"cases":len(ids),"baselinePassed":sum(cases[i]["category"] in supported for i in ids),
                             "candidatePassed":sum(bool(observations[i]["candidatePassed"]) for i in ids)}
    latencies=[float(observations[case["caseId"]]["latencyMicros"])/1000 for case in sealed]
    critical=sum(1 for case in sealed if case["criticalBoundary"] and not observations[case["caseId"]]["candidatePassed"])
    report={"suite":"memory-lifecycle-v1","developmentCases":20,"sealedCases":80,
            "baselineRevision":baseline["revision"],"candidateRevision":candidate["candidateRevision"],
            "baselineSuccessRate":sum(baseline_values)/len(sealed),"candidateSuccessRate":sum(candidate_values)/len(sealed),
            "differencePercentagePoints":100*sum(diffs)/len(sealed),"pairedBootstrap95Pct":_bootstrap(diffs,20260712,2000),
            "criticalBoundaryViolations":critical,"p50LatencyMs":_percentile(latencies,.50),
            "p95LatencyMs":_percentile(latencies,.95),"breakdown":breakdown,"seed":20260712,
            "bootstrapSamples":2000,"datasetSha256":_sha(dataset_path),"baselineManifestSha256":_sha(baseline_path),
            "candidateObservationsSha256":_sha(candidate_path),
            "scope":"application/tool-contract lifecycle benchmark; final conversational E2E reported separately"}
    output_path.parent.mkdir(parents=True,exist_ok=True)
    output_path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    return report
