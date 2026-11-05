"""Verify mapping publication and smoke-test cleanup using isolated files and fake processes."""

import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch


def load_script(name):
    """Import a repository build script without executing its command-line entry point."""
    path = Path(__file__).resolve().parent.parent / '.github/scripts' / (name + '.py')
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


MAPPINGS = load_script('generate-mappings')
"""Mapping workflow imported for isolated filesystem and subprocess checks."""

SMOKE = load_script('server-smoke')
"""Server smoke workflow imported without launching Minecraft or opening sockets."""


class BuildScriptTest(unittest.TestCase):
    """Protect mapping-output integrity and owned-process cleanup during failures."""

    def test_manifest_versions_include_additional_mappings_once(self):
        """Retain manifest order and extra mappings while removing duplicate version IDs."""
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest = {'first': {'gameVersions': ['old', 'shared'],
                                  'additionalMappingVersions': ['extra']},
                        'second': {'gameVersions': ['shared', 'new']}}
            (root / 'supported-versions.json').write_text(json.dumps(manifest))
            self.assertEqual(['old', 'shared', 'extra', 'new'], MAPPINGS.supported_versions(root))

    def test_missing_output_fails_even_after_successful_server_exit(self):
        """Prevent a bootstrap that exits without saving mappings from being treated as successful."""
        with tempfile.TemporaryDirectory() as folder:
            work = Path(folder)
            with patch.object(MAPPINGS, 'server_launcher', return_value=work / 'launcher.jar'), \
                    patch.object(MAPPINGS.time, 'sleep'), patch.object(MAPPINGS.subprocess, 'run'):
                with self.assertRaisesRegex(RuntimeError, 'did not write output'):
                    MAPPINGS.generate_version(work, 'probe')

    def test_failed_matrix_does_not_replace_checked_in_mappings(self):
        """Leave the repository resource untouched when a later version fails after earlier output."""
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            resource = root / 'common/src/main/resources/raknetify-channel-mappings.json'
            resource.parent.mkdir(parents=True)
            resource.write_bytes(b'original mappings')
            jar = root / 'fabric-universal/build/libs/raknetify2-fabric-probe.jar'
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b'probe jar')

            def generate(work, version):
                """Save the first fake result, then simulate a later server bootstrap failure."""
                if version == 'first':
                    (work / 'channelMappings.json').write_bytes(b'partial mappings')
                else:
                    raise subprocess.CalledProcessError(1, ['java'])

            with patch.object(MAPPINGS, '__file__', str(root / '.github/scripts/generate-mappings.py')), \
                    patch.object(MAPPINGS.sys, 'argv', ['generate-mappings.py', 'first', 'second']), \
                    patch.object(MAPPINGS.subprocess, 'run'), \
                    patch.object(MAPPINGS, 'generate_version', side_effect=generate):
                with self.assertRaises(subprocess.CalledProcessError):
                    MAPPINGS.main()
            self.assertEqual(b'original mappings', resource.read_bytes())

    def test_server_cleanup_kills_only_after_graceful_timeout(self):
        """Force termination after an owned server ignores stop, then finish draining its log."""
        process = Mock()
        process.poll.return_value = None
        process.wait.side_effect = [subprocess.TimeoutExpired('java', 45), 0]
        reader = Mock()
        SMOKE.stop_server(process, reader)
        process.stdin.write.assert_called_once_with('stop\n')
        process.kill.assert_called_once_with()
        reader.join.assert_called_once_with(timeout=10)

    def test_exited_server_is_not_stopped_again(self):
        """Join output collection without sending commands or killing an already exited process."""
        process = Mock()
        process.poll.return_value = 0
        SMOKE.stop_server(process, Mock())
        process.stdin.write.assert_not_called()
        process.kill.assert_not_called()

    def test_readiness_rejects_early_server_exit(self):
        """Fail immediately when the child exits before announcing readiness."""
        process = Mock()
        process.poll.return_value = 1
        with self.assertRaisesRegex(RuntimeError, 'exited before readiness'):
            SMOKE.await_ready(process, Mock())


if __name__ == '__main__':
    unittest.main()
