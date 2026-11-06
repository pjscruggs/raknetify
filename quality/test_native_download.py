"""Exercise native runtime downloads against a real local HTTP server and verified content hashes."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
from pathlib import Path
import tempfile
import threading
import unittest


class NativeDownloadTests(unittest.TestCase):
    """Require retries to recover complete content without admitting corrupt or stale cached artifacts."""

    def setUp(self):
        """Start an isolated HTTP endpoint with controllable failures and a disposable artifact cache."""
        spec = importlib.util.spec_from_file_location('native_loader',
                Path(__file__).resolve().parents[1] / '.github/scripts/native-loader-smoke.py')
        self.native = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.native)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.artifact = Path(self.temporary.name) / 'artifact.jar'
        self.payload = b'verified runtime fixture'
        self.digest = hashlib.sha1(self.payload).hexdigest()
        self.requests = 0
        self.failures = 0
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), self.handler())
        self.worker = threading.Thread(target=self.server.serve_forever)
        self.worker.start()
        self.addCleanup(self.stop_server)
        self.url = 'http://127.0.0.1:' + str(self.server.server_port) + '/artifact.jar'

    def handler(self):
        """Bind each request handler to this test's counters, response bytes and transient failure budget."""
        test = self

        class Handler(BaseHTTPRequestHandler):
            """Serve deterministic successes or retryable failures without reaching a public dependency host."""

            def do_GET(self):
                """Return one bounded response and count every actual network attempt."""
                test.requests += 1
                failed = test.requests <= test.failures
                body = b'temporary failure' if failed else test.payload
                self.send_response(503 if failed else 200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format, *args):
                """Keep the local server's access log out of test output, while curl reports transfer failures."""

        return Handler

    def stop_server(self):
        """Stop and join the test-owned HTTP worker before deleting its temporary artifact directory."""
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=5)
        self.assertFalse(self.worker.is_alive())

    def test_recovers_transient_failure(self):
        """Retry a real HTTP 503 and promote only the complete response whose content matches the expected hash."""
        self.failures = 1
        self.native.download(self.artifact, self.url, self.digest)
        self.assertEqual(2, self.requests)
        self.assertEqual(self.payload, self.artifact.read_bytes())
        self.assertFalse(self.artifact.with_suffix('.jar.part').exists())

    def test_rejects_changed_response(self):
        """Leave unexpected server bytes unpromoted instead of treating a successful HTTP response as valid content."""
        with self.assertRaises(AssertionError):
            self.native.download(self.artifact, self.url, '0' * 40)
        self.assertFalse(self.artifact.exists())

    def test_reuses_only_verified_cache(self):
        """Avoid another request for verified cached bytes, but reject corruption discovered on a later read."""
        self.artifact.write_bytes(self.payload)
        self.native.download(self.artifact, self.url, self.digest)
        self.assertEqual(0, self.requests)
        self.artifact.write_bytes(b'changed cache')
        with self.assertRaises(AssertionError):
            self.native.download(self.artifact, self.url, self.digest)
        self.assertEqual(0, self.requests)


if __name__ == '__main__':
    unittest.main()
