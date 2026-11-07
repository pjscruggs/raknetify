"""Reject altered executable dependencies with and without Python assertion optimization."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


def script(name):
    """Import a fixture's download functions without launching its command-line entry point."""
    path = Path(__file__).resolve().parents[1] / '.github/scripts' / (name + '.py')
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


class DownloadIntegrityTests(unittest.TestCase):
    """Exercise real download guards with local bytes in place of external HTTP responses."""

    def setUp(self):
        """Give each case an empty cache and a digest for known, intentionally non-executable bytes."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        self.data = b'expected artifact bytes'
        self.entry = {'url': 'https://example.invalid/artifact.jar', 'filename': 'artifact.jar',
                      'sha512': hashlib.sha512(self.data).hexdigest()}

    def test_client_cache(self):
        """Accept a matching download, then reject a corrupt cache without trusting its existing filename."""
        module = script('client-address-check')
        with patch.object(module.urllib.request, 'urlopen', return_value=io.BytesIO(self.data)):
            target = module.download(self.folder, self.entry['url'], self.entry['sha512'], 'sha512')
        self.assertEqual(self.data, target.read_bytes())
        target.write_bytes(b'altered')
        with self.assertRaisesRegex(ValueError, 'digest'):
            module.download(self.folder, self.entry['url'], self.entry['sha512'], 'sha512')

    def test_proxy_cache(self):
        """Check both the first proxy artifact and every reuse of the cached executable."""
        module = script('proxy-smoke')
        target = self.folder / 'proxy.jar'
        with patch.object(module.urllib.request, 'urlopen', return_value=io.BytesIO(self.data)):
            module.download(self.entry, target)
        target.write_bytes(b'altered')
        with self.assertRaisesRegex(ValueError, 'digest'):
            module.download(self.entry, target)

    def test_native_download(self):
        """Never promote a corrupt partial download or accept a corrupt native loader already in the cache."""
        module = script('native-loader-smoke')
        target = self.folder / 'installer.jar'
        partial = self.folder / 'installer.jar.part'
        partial.write_bytes(b'altered')
        with patch.object(module.subprocess, 'run'), self.assertRaisesRegex(ValueError, 'digest'):
            module.download(target, self.entry['url'], self.entry['sha512'], 'sha512')
        self.assertFalse(target.exists())
        partial.write_bytes(self.data)
        with patch.object(module.subprocess, 'run'):
            module.download(target, self.entry['url'], self.entry['sha512'], 'sha512')
        self.assertEqual(self.data, target.read_bytes())
        target.write_bytes(b'altered')
        with self.assertRaisesRegex(ValueError, 'digest'):
            module.download(target, self.entry['url'], self.entry['sha512'], 'sha512')

    def test_assets(self):
        """Enforce the asset digest before promotion and during cache reuse, including optimized runs."""
        module = script('native-loader-smoke')
        target = self.folder / 'asset'
        partial = self.folder / 'asset.part'
        transfers = [(target, self.entry['url'], hashlib.sha1(self.data).hexdigest())]
        partial.write_bytes(b'altered')
        with patch.object(module.subprocess, 'run'), self.assertRaisesRegex(ValueError, 'digest'):
            module.download_assets(transfers)
        self.assertFalse(target.exists())
        partial.write_bytes(self.data)
        with patch.object(module.subprocess, 'run'):
            module.download_assets(transfers)
        self.assertEqual(self.data, target.read_bytes())
        target.write_bytes(b'altered')
        with self.assertRaisesRegex(ValueError, 'digest'):
            module.download_assets(transfers)

    def test_minecraft_metadata(self):
        """Reject a changed launcher document before its library URLs can enter the download plan."""
        module = script('native-loader-smoke')
        content = b'{"libraries":[]}'
        entry = {'id': 'fixture', 'url': self.entry['url'], 'sha1': hashlib.sha1(content).hexdigest()}
        manifest = json.dumps({'versions': [entry]}).encode()
        for body in (content, b'{"libraries":["altered"]}'):
            with self.subTest(body=body), patch.object(module.urllib.request, 'urlopen',
                                                     side_effect=[io.BytesIO(manifest), io.BytesIO(body)]):
                if body == content:
                    self.assertEqual({'libraries': []}, module.minecraft_metadata('fixture'))
                else:
                    with self.assertRaisesRegex(ValueError, 'digest'):
                        module.minecraft_metadata('fixture')

    def test_server_mod_install(self):
        """A rejected server dependency must not overwrite a previously verified mod file."""
        module = script('server-smoke')
        (self.folder / 'mods').mkdir()
        target = self.folder / 'mods/artifact.jar'
        with patch.object(module.urllib.request, 'urlopen', return_value=io.BytesIO(self.data)):
            module.install_mods(self.folder, [self.entry])
        with (patch.object(module.urllib.request, 'urlopen', return_value=io.BytesIO(b'altered')),
              self.assertRaisesRegex(ValueError, 'digest')):
            module.install_mods(self.folder, [self.entry])
        self.assertEqual(self.data, target.read_bytes())

    def test_voice_mod_install(self):
        """A changed voice dependency must not be installed or trigger configuration changes."""
        module = script('voicechat-fixture')
        (self.folder / 'mods').mkdir()
        with (patch.object(module.Path, 'read_text', return_value=json.dumps({'fixture': self.entry})),
              patch.object(module.urllib.request, 'urlopen', return_value=io.BytesIO(b'altered')),
              self.assertRaisesRegex(ValueError, 'digest')):
            module.install(self.folder, 'fixture')
        self.assertFalse((self.folder / 'mods/voicechat.jar').exists())
        self.assertFalse((self.folder / 'config').exists())


class OptimizedDownloadIntegrityTests(unittest.TestCase):
    """Keep the normal quality gate responsible for checking Python's optimized execution mode too."""

    def test_optimized_interpreter(self):
        """Run the same corruption witnesses in a fresh interpreter that removes all assert statements."""
        result = subprocess.run([sys.executable, '-O', '-m', 'unittest',
                                 'quality.test_download_integrity.DownloadIntegrityTests'],
                                cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
