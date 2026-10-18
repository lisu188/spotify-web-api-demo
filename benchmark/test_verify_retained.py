import json
import shutil
import tempfile
import unittest
import zipfile
from pathlib import Path

import verify_retained


class RetainedEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.harness = Path(__file__).resolve().parent
        self.results = self.root / "results"
        verify_retained.restore(self.harness / "evidence/2026-10-18/evidence.zip", self.results)
        self.baseline = "5369a02692f026d989b527dfa19ba74799382045"
        self.optimized = "0ae29dd1be1233ca883aeea3586a025eee38447d"

    def verify(self, harness=None):
        verify_retained.verify(self.results, harness or self.harness, self.baseline, self.optimized)

    def test_accepts_complete_retained_measurements(self):
        self.verify()

    def test_rejects_missing_trial(self):
        next(self.results.glob("*/*/trial-*.json")).unlink()
        with self.assertRaisesRegex(ValueError, "48 measured trials"):
            self.verify()

    def test_rejects_tampered_source_archive(self):
        (self.results / "optimized-source.tar").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "archive hash"):
            self.verify()

    def test_rejects_tampered_result(self):
        path = next(self.results.glob("*/*/trial-*.json"))
        row = json.loads(path.read_text(encoding="utf-8"))
        row["elapsedMs"] += 1
        path.write_text(json.dumps(row), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Summary differs|raw.csv differs"):
            self.verify()

    def test_rejects_executable_harness_drift(self):
        harness = self.root / "harness"
        run = json.loads((self.results / "run.json").read_text(encoding="utf-8"))
        for name in run["harnessSha256"]:
            target = harness / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(self.harness / name, target)
        with (harness / "run.sh").open("a", encoding="utf-8") as handle:
            handle.write("\n# tampered\n")
        with self.assertRaisesRegex(ValueError, "Executable harness hash"):
            self.verify(harness)

    def test_rejects_zip_traversal(self):
        archive = self.root / "unsafe.zip"
        with zipfile.ZipFile(archive, "x") as snapshot:
            snapshot.writestr("../escape", "bad")
        with self.assertRaisesRegex(ValueError, "Unsafe archive path"):
            verify_retained.restore(archive, self.root / "unsafe-output")
        self.assertFalse((self.root / "escape").exists())


if __name__ == "__main__":
    unittest.main()
