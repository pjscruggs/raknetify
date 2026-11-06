"""Verify the native login fixture's credential boundaries through actual loopback HTTP requests."""
import importlib.util
import json
from pathlib import Path
import unittest
import urllib.error
import urllib.request


def load_fixture():
    """Load the test-only session authority without starting a listener at import time."""
    path = Path(__file__).resolve().parents[1] / '.github/scripts/session-fixture.py'
    spec = importlib.util.spec_from_file_location('session_fixture', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SessionFixtureTest(unittest.TestCase):
    """Ensure native test authentication cannot silently use real credentials or accept mismatched sessions."""

    def request(self, origin, path, body=None):
        """Send a real bounded HTTP request and return error responses without printing their request bodies."""
        data = None if body is None else json.dumps(body).encode('utf-8')
        request = urllib.request.Request(origin + path, data=data, headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            with error:
                return error.code, error.read()

    def test_only_synthetic_credentials_can_register_a_session(self):
        """Reject a nonfixture token and a different profile even when their session digest is well formed."""
        fixture = load_fixture()
        with fixture.SessionAuthority() as authority:
            for token, profile in [('unexpected', fixture.PLAYER_ID), ('0', '1' * 32)]:
                status, _ = self.request(authority.origin, '/join',
                                         {'accessToken': token, 'selectedProfile': profile, 'serverId': 'abc'})
                self.assertEqual(403, status)
            self.assertEqual(set(), authority.sessions)

    def test_native_client_and_server_must_agree_on_the_digest(self):
        """Accept matching session evidence but reject a changed digest before returning a player profile."""
        fixture = load_fixture()
        with fixture.SessionAuthority() as authority:
            status, _ = self.request(authority.origin, '/join',
                                     {'accessToken': '0', 'selectedProfile': fixture.PLAYER_ID, 'serverId': '-abc'})
            self.assertEqual(204, status)
            status, _ = self.request(authority.origin, '/verify?username=TransportProbe&serverId=abc')
            self.assertEqual(204, status)
            self.assertEqual([], authority.accepted)
            status, body = self.request(authority.origin, '/verify?username=TransportProbe&serverId=-abc')
            self.assertEqual(200, status)
            self.assertEqual(fixture.PLAYER_ID, json.loads(body)['id'])

    def test_malformed_or_oversized_requests_do_not_register_sessions(self):
        """Reject scalar JSON and an oversized body before the credential handler consumes either request."""
        fixture = load_fixture()
        with fixture.SessionAuthority() as authority:
            self.assertEqual(400, self.request(authority.origin, '/join', [1, 2])[0])
            self.assertEqual(413, self.request(authority.origin, '/join', {'padding': 'x' * 4096})[0])
            self.assertEqual(set(), authority.sessions)

    def test_discovery_never_advertises_real_authentication_services(self):
        """Keep all service destinations on the fixture origin, including unused client endpoints."""
        fixture = load_fixture()
        with fixture.SessionAuthority() as authority:
            status, body = self.request(authority.origin, '/discovery')
            self.assertEqual(200, status)
            services = json.loads(body)['discovery']
            for service in services.values():
                for endpoint in service['endpoints'].values():
                    urls = endpoint.get('validUris', [endpoint.get('uri')])
                    self.assertTrue(all(url.startswith(authority.origin + '/') for url in urls))

    def test_profile_lookup_returns_only_the_synthetic_identity(self):
        """Provide the native server's profile lookup without fabricating responses for unrelated accounts."""
        fixture = load_fixture()
        with fixture.SessionAuthority() as authority:
            status, body = self.request(authority.origin, '/profile/' + fixture.PLAYER_ID + '?unsigned=false')
            self.assertEqual(200, status)
            self.assertEqual('TransportProbe', json.loads(body)['name'])
            self.assertEqual(404, self.request(authority.origin, '/profile/' + '1' * 32)[0])
            self.assertEqual([], authority.accepted)


if __name__ == '__main__':
    unittest.main()
