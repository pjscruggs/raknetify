"""Verify that the forwarding test fixture preserves client isolation and never changes an unrelated policy."""
import importlib.util
import json
from pathlib import Path
import socket
import tempfile
import unittest


def load_fixture():
    """Import the loopback relay without launching a Minecraft server or creating configuration files."""
    path = Path(__file__).resolve().parents[1] / '.github/scripts/udp-proxy-fixture.py'
    spec = importlib.util.spec_from_file_location('udp_proxy_fixture', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class UdpProxyFixtureTest(unittest.TestCase):
    """Exercise actual UDP tuples, first-packet metadata, late replies and safe fixture configuration."""

    def test_policy_refuses_unrelated_contents(self):
        """Reject an existing operator policy and remove only the exact policy created by this fixture."""
        fixture = load_fixture()
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            fixture.configure(folder, True)
            path = folder / 'config/raknetify2-proxy-protocol.json'
            self.assertEqual(json.loads(path.read_text()), fixture.POLICY)
            fixture.configure(folder, False)
            self.assertFalse(path.exists())
            path.write_text('{"trustedProxies":["192.0.2.1"]}')
            original = path.read_bytes()
            with self.assertRaises(AssertionError):
                fixture.configure(folder, False)
            self.assertEqual(path.read_bytes(), original)

    def test_first_only_and_repeated_headers(self):
        """Verify exact wire headers, unchanged return packets and distinct backend tuples in both modes."""
        for repeated in (False, True):
            with self.subTest(repeated=repeated):
                self.exchange(repeated)

    def exchange(self, repeated):
        """Keep one client alive after another closes, including a late reply to the closed peer."""
        fixture = load_fixture()
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as backend:
            backend.bind(('127.0.0.1', 0))
            backend.settimeout(3)
            with fixture.UdpProxy(backend.getsockname()[1], repeated) as frontend:
                first_peer = self.one_client(backend, frontend)
                backend.sendto(b'late closed-client reply', first_peer)
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                    client.settimeout(3)
                    client.sendto(b'first', ('127.0.0.1', frontend))
                    first, peer = backend.recvfrom(65535)
                    self.assertNotEqual(peer, first_peer)
                    self.assertEqual(first[:16], bytes.fromhex('0d0a0d0a000d0a515549540a2112000c'))
                    self.assertEqual(first[28:], b'first')
                    client.sendto(b'second', ('127.0.0.1', frontend))
                    second, same_peer = backend.recvfrom(65535)
                    self.assertEqual(same_peer, peer)
                    self.assertEqual(second, first[:28] + b'second' if repeated else b'second')
                    backend.sendto(b'unchanged return', peer)
                    self.assertEqual(client.recv(65535), b'unchanged return')

    def one_client(self, backend, frontend):
        """Finish one peer normally, leaving its backend tuple available for a late-response regression."""
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            client.settimeout(3)
            client.sendto(b'initial', ('127.0.0.1', frontend))
            packet, peer = backend.recvfrom(65535)
            self.assertEqual(packet[28:], b'initial')
            backend.sendto(b'complete', peer)
            self.assertEqual(client.recv(65535), b'complete')
            return peer
