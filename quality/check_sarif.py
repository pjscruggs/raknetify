"""Require complete CodeQL reports without findings before producing verified downloads."""
import json
from pathlib import Path
import sys


def report_findings(path):
    """Count every reported result and reject missing analysis data instead of treating it as clean."""
    report = json.loads(path.read_text(encoding='utf-8-sig'))
    if report.get('version') != '2.1.0' or not report.get('runs'):
        raise ValueError('Missing SARIF 2.1.0 analysis runs: ' + path.name)
    count = 0
    for run in report['runs']:
        if run.get('tool', {}).get('driver', {}).get('name') != 'CodeQL':
            raise ValueError('Unexpected analysis tool: ' + path.name)
        if not isinstance(run.get('results'), list):
            raise ValueError('Missing analysis results: ' + path.name)
        count += len(run['results'])
    return count


def verify_reports(directory):
    """Fail closed on absent, incomplete or nonempty reports, including informational or suppressed findings."""
    paths = sorted(directory.glob('*.sarif'))
    if not paths:
        raise ValueError('No CodeQL SARIF reports were produced')
    count = sum(report_findings(path) for path in paths)
    if count:
        raise ValueError(f'CodeQL reported {count} findings. Review the uploaded SARIF reports.')
    return len(paths)


def main():
    """Validate the requested output directory and return a failing process status for any report problem."""
    if len(sys.argv) != 2:
        raise SystemExit('Usage: check_sarif.py REPORT_DIRECTORY')
    try:
        count = verify_reports(Path(sys.argv[1]))
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise SystemExit(str(error)) from error
    print(f'Passed {count} CodeQL reports with zero findings')


if __name__ == '__main__':
    main()
