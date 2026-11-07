"""Exercise publication guards against untrusted runs, changed bytes and stale loader metadata."""
import hashlib
import io
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


def release_module():
    """Load the publication guard without triggering its command-line entry point or any network calls."""
    path = Path(__file__).resolve().parents[1] / '.github/scripts/release-plan.py'
    spec = importlib.util.spec_from_file_location('release_plan', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ReleasePlanTests(unittest.TestCase):
    """Keep publication bound to passing main builds and their exact production JARs."""

    def setUp(self):
        """Create an isolated artifact directory and load the guard for each independent regression."""
        self.guard = release_module()
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.version = '0.1.0-alpha.1.285'

    def trusted_run(self):
        """Build the minimal successful main-run metadata accepted by the provenance gate."""
        return {'head_repository': {'full_name': 'pjscruggs/raknetify2'}, 'path': '.github/workflows/build.yml',
                'event': 'push', 'head_branch': 'main', 'status': 'completed', 'conclusion': 'success',
                'head_sha': 'a' * 40}

    def make_bundle(self):
        """Create three small loader JARs with authentic metadata shapes and matching release checksums."""
        (self.folder / 'compatibility.json').write_text(json.dumps({'version': self.version}), encoding='utf-8')
        for loader in ('fabric', 'forge', 'neoforge'):
            path = self.folder / ('raknetify2-' + loader + '-' + self.version + '.jar')
            with zipfile.ZipFile(path, 'w') as archive:
                if loader == 'fabric':
                    archive.writestr('fabric.mod.json', json.dumps({'id': 'raknetify', 'version': self.version}))
                else:
                    filename = 'mods.toml' if loader == 'forge' else 'neoforge.mods.toml'
                    archive.writestr('META-INF/' + filename, '[[mods]]\nmodId="raknetify"\nversion="' + self.version + '"\n')
        self.checksums()

    def checksums(self):
        """Regenerate fixture checksums when a test needs to distinguish metadata failures from byte corruption."""
        files = sorted(path for path in self.folder.iterdir() if path.name != 'SHA256SUMS')
        content = ''.join(hashlib.sha256(path.read_bytes()).hexdigest() + '  ./' + path.name + '\n' for path in files)
        (self.folder / 'SHA256SUMS').write_text(content, encoding='utf-8')

    def test_accepts_successful_main(self):
        """Permit the exact provenance expected from the release-gating build workflow."""
        self.assertEqual('a' * 40, self.guard.validate_run(self.trusted_run(), 'pjscruggs/raknetify2'))

    def test_rejects_minimized_nested_entrypoint(self):
        """Check both positive and missing-class cases inside a runtime nested in the universal Fabric JAR."""
        for present in (True, False):
            nested = io.BytesIO()
            with zipfile.ZipFile(nested, 'w') as runtime:
                runtime.writestr('fabric.mod.json', json.dumps({'entrypoints': {'voicechat': ['example.Voice']}}))
                if present:
                    runtime.writestr('example/Voice.class', b'fixture')
            outer = io.BytesIO()
            with zipfile.ZipFile(outer, 'w') as universal:
                universal.writestr('fabric.mod.json', json.dumps({'jars': [{'file': 'META-INF/jars/runtime.jar'}]}))
                universal.writestr('META-INF/jars/runtime.jar', nested.getvalue())
            with zipfile.ZipFile(outer) as artifact:
                if present:
                    self.guard.verify_fabric_entrypoints(artifact)
                else:
                    with self.assertRaisesRegex(ValueError, 'Missing Fabric entrypoint'):
                        self.guard.verify_fabric_entrypoints(artifact)

    def test_rejects_untrusted_or_incomplete_runs(self):
        """Reject forks, PRs, other workflows, non-main branches, failures and unfinished builds independently."""
        changes = [('head_repository', {'full_name': 'other/raknetify2'}), ('event', 'pull_request'),
                   ('path', '.github/workflows/publish.yml'), ('head_branch', 'feature'),
                   ('conclusion', 'failure'), ('status', 'in_progress'), ('head_sha', '../main')]
        for key, value in changes:
            with self.subTest(key=key):
                run = self.trusted_run()
                run[key] = value
                with self.assertRaises(ValueError):
                    self.guard.validate_run(run, 'pjscruggs/raknetify2')

    def test_accepts_exact_bundle(self):
        """Allow a complete clean bundle when all checksums and embedded loader versions agree."""
        self.make_bundle()
        self.assertEqual(self.version, self.guard.verify_artifacts(self.folder))

    def test_optimized_python_still_validates_release_bytes(self):
        """Run the actual publication CLI with optimization and require both valid acceptance and corrupt rejection."""
        self.make_bundle()
        script = Path(__file__).resolve().parents[1] / '.github/scripts/release-plan.py'
        command = [sys.executable, '-O', str(script), '--artifacts', str(self.folder)]
        environment = os.environ.copy()
        # Require local JSON output without writing fixture metadata into the enclosing workflow.
        environment.pop('GITHUB_OUTPUT', None)
        accepted = subprocess.run(command, capture_output=True, text=True, check=False, timeout=15, env=environment)
        self.assertEqual(0, accepted.returncode, accepted.stderr)
        self.assertEqual({'version': self.version}, json.loads(accepted.stdout))
        (self.folder / 'compatibility.json').write_text('{}', encoding='utf-8')
        rejected = subprocess.run(command, capture_output=True, text=True, check=False, timeout=15, env=environment)
        self.assertNotEqual(0, rejected.returncode)
        self.assertIn('Changed artifact', rejected.stderr)

    def test_invalid_run_is_rejected_before_network_access_when_optimized(self):
        """Reject a malformed run identifier under optimization before it can select a remote API resource."""
        script = Path(__file__).resolve().parents[1] / '.github/scripts/release-plan.py'
        result = subprocess.run([sys.executable, '-O', str(script), '--run-id', '../other'],
                                capture_output=True, text=True, check=False, timeout=15)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('A numeric build run ID is required', result.stderr)

    def test_rejects_changed_bytes(self):
        """Fail before publication if any artifact changes after the build produced its checksum inventory."""
        self.make_bundle()
        (self.folder / 'compatibility.json').write_text('{}', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Changed artifact'):
            self.guard.verify_artifacts(self.folder)

    def test_rejects_unlisted_files(self):
        """Prevent an accidental fixture or stale extra JAR from entering the verified release directory."""
        self.make_bundle()
        (self.folder / 'unexpected.jar').write_bytes(b'not a release artifact')
        with self.assertRaisesRegex(ValueError, 'inventory'):
            self.guard.verify_artifacts(self.folder)

    def test_rejects_path_traversal(self):
        """Reject checksum entries pointing outside the artifact directory even if their digest is well formed."""
        (self.folder / 'SHA256SUMS').write_text('a' * 64 + '  ./../outside.jar\n', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Invalid checksum'):
            self.guard.verify_checksums(self.folder)

    def test_rejects_dirty_release(self):
        """Keep local uncommitted development builds out of live publication."""
        self.version += '-dirty'
        self.make_bundle()
        with self.assertRaisesRegex(ValueError, 'clean release'):
            self.guard.verify_artifacts(self.folder)

    def test_rejects_stale_embedded_version(self):
        """Detect a renamed old native JAR even when its current checksum is included in the bundle."""
        self.make_bundle()
        path = self.folder / ('raknetify2-forge-' + self.version + '.jar')
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('META-INF/mods.toml', '[[mods]]\nmodId="raknetify"\nversion="old"\n')
        self.checksums()
        with self.assertRaisesRegex(ValueError, 'metadata version'):
            self.guard.verify_artifacts(self.folder)

    def test_rejects_packaged_fixture(self):
        """Keep test-only lifecycle callbacks and automatic join code out of public mod JARs."""
        self.make_bundle()
        path = self.folder / ('raknetify2-forge-' + self.version + '.jar')
        with zipfile.ZipFile(path, 'a') as archive:
            archive.writestr('com/ishland/raknetify/test/NativeClientTests.class', b'fixture')
        self.checksums()
        with self.assertRaisesRegex(ValueError, 'Test fixture'):
            self.guard.verify_artifacts(self.folder)

    def test_selective_retry_matrix(self):
        """Support replaying only an unsuccessful loader and destination without scheduling the other uploads."""
        self.assertEqual({'include': [{'loader': 'forge', 'destination': 'curseforge'}]},
                         self.guard.publication_matrix('forge', 'curseforge'))
        self.assertEqual(6, len(self.guard.publication_matrix('all', 'both')['include']))

    def test_checks_spigot_embedded_version_when_present(self):
        """Allow older mod-only releases but reject a mislabeled plugin added to a newer verified bundle."""
        self.make_bundle()
        path = self.folder / ('raknetify2-spigot-' + self.version + '.jar')
        for embedded in (self.version, 'old'):
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('plugin.yml', "name: Raknetify2\nversion: '" + embedded
                                 + "'\nmain: com.ishland.raknetify.spigot.RaknetifySpigotPlugin\n")
            self.checksums()
            if embedded == self.version:
                self.assertEqual(self.version, self.guard.verify_artifacts(self.folder))
            else:
                with self.assertRaisesRegex(ValueError, 'metadata version'):
                    self.guard.verify_artifacts(self.folder)

    def test_spigot_publication_does_not_target_a_mod_project(self):
        """Publish server plugins with Modrinth metadata and reject uploading them to the CurseForge mod project."""
        self.assertEqual({'include': [{'loader': 'spigot', 'destination': 'modrinth'}]},
                         self.guard.publication_matrix('spigot', 'modrinth'))
        for destination in ('curseforge', 'both'):
            with self.assertRaisesRegex(ValueError, 'Bukkit project'):
                self.guard.publication_matrix('spigot', destination)

    def test_all_mods_excludes_server_plugins(self):
        """Keep the explicit bulk-mod selection compatible with older releases that do not contain server plugins."""
        self.assertEqual(self.guard.publication_matrix('all', 'both'),
                         self.guard.publication_matrix('all-mods', 'both'))


if __name__ == '__main__':
    unittest.main()
