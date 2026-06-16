from pathlib import Path
import unittest

from chatagent_eval.question_set import (
    apply_overlay,
    canonical_hash,
    overlay_hash,
    parse_overlay,
    read_json,
    split_hashes,
    text_hash,
)


FIXTURES = Path(__file__).parent / "fixtures"


class HashCanonicalizationTest(unittest.TestCase):
    def test_all_hash_domains_match_shared_expected_fixture(self) -> None:
        base = read_json(FIXTURES / "question-set-base.json")
        overlay = parse_overlay(FIXTURES / "question-set-overlay.jsonl", base)
        expected = read_json(FIXTURES / "question-set-hashes.json")

        self.assertEqual(expected["baseHash"], canonical_hash(base))
        self.assertEqual(expected["splitHashes"], split_hashes(base))
        self.assertEqual(expected["overlayHash"], overlay_hash(overlay))
        self.assertEqual(expected["effectiveBaseHash"], canonical_hash(apply_overlay(base, overlay)))
        self.assertEqual(expected["textHashes"]["question"], text_hash("line one\r\nline two"))
        self.assertEqual(expected["textHashes"]["referenceAnswer"], text_hash("Café\rvalue"))
        self.assertEqual(expected["textHashes"]["referenceContent"], text_hash("Δ evidence\r\nline"))


if __name__ == "__main__":
    unittest.main()
