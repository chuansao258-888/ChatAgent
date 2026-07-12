import json
import tempfile
import unittest
from pathlib import Path
from chatagent_eval.memory_lifecycle_runner import run

class MemoryLifecycleRunnerTest(unittest.TestCase):
    def test_paired_report_is_reproducible_and_sealed(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); cases=[]; observations=[]
            for i in range(100):
                split="development" if i<20 else "sealed"; category="write_recall" if i%4==0 else "correction"
                required=["conversation_correction","ambiguous_target","stale_update","deletion","cross_user_isolation","stored_prompt_injection"]
                category=required[i%len(required)]
                cases.append({"caseId":f"c{i}","sourceGroupId":f"g{i}","split":split,"category":category,"criticalBoundary":category=="stored_prompt_injection"})
                observations.append({"caseId":f"c{i}","candidatePassed":True,"latencyMicros":100+i})
            dataset=root/"dataset.json"; baseline=root/"baseline.json"; candidate=root/"candidate.json"
            dataset.write_text(json.dumps({"cases":cases})); baseline.write_text(json.dumps({"revision":"base","supportedCategories":["write_recall"]}))
            candidate.write_text(json.dumps({"candidateRevision":"candidate","observations":observations}))
            first=run(dataset,baseline,candidate,root/"one.json")
            second=run(dataset,baseline,candidate,root/"two.json")
            self.assertEqual(first,second)
            self.assertEqual(80,first["sealedCases"])
            self.assertGreater(first["pairedBootstrap95Pct"][0],0)
            self.assertEqual(0,first["criticalBoundaryViolations"])
