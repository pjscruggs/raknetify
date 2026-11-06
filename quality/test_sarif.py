"""Keep the source-security release gate from accepting missing or filtered analysis results."""
import json
from pathlib import Path
import tempfile
import unittest

from quality.check_sarif import verify_reports


class SarifGateTests(unittest.TestCase):
    """Exercise complete, incomplete and vulnerable reports without depending on the scanner service."""

    def setUp(self):
        """Allocate a separate report directory so missing-output checks cannot reuse another fixture."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)

    def write_report(self, results):
        """Write the CodeQL report envelope around the exact result list under test."""
        report = {'version': '2.1.0', 'runs': [
            {'tool': {'driver': {'name': 'CodeQL'}}, 'results': results}]}
        (self.folder / 'java.sarif').write_text(json.dumps(report), encoding='utf-8')

    def test_clean_report_passes(self):
        """Accept an explicitly empty result list from a completed CodeQL report."""
        self.write_report([])
        self.assertEqual(1, verify_reports(self.folder))

    def test_missing_report_fails(self):
        """Missing scanner output must never make a release appear secure."""
        with self.assertRaisesRegex(ValueError, 'No CodeQL'):
            verify_reports(self.folder)

    def test_missing_results_fail(self):
        """Reject a null result field instead of interpreting an incomplete report as empty."""
        self.write_report(None)
        with self.assertRaisesRegex(ValueError, 'Missing analysis results'):
            verify_reports(self.folder)

    def test_all_findings_fail(self):
        """Warnings, notes and explicit suppressions all remain findings for the release gate."""
        for result in ({'level': 'warning'}, {'level': 'note'}, {'suppressions': [{'kind': 'inSource'}]}):
            with self.subTest(result=result):
                self.write_report([result])
                with self.assertRaisesRegex(ValueError, '1 findings'):
                    verify_reports(self.folder)


if __name__ == '__main__':
    unittest.main()
