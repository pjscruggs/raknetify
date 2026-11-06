"""Provide an isolated session authority for native encrypted logins using only a fixed synthetic identity."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import threading
from urllib.parse import parse_qs, urlsplit
import uuid


PLAYER_ID = uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:TransportProbe').digest(), version=3).hex
"""Offline fixture identity shared by the client launcher, whitelist, and local session responses."""


def discovery(origin):
    """Direct every advertised service to this loopback fixture so synthetic credentials never reach a real service."""
    groups = {
        'authentication': ['getPublicKeys', 'loginXbox'],
        'session': ['getProfileById', 'verify', 'join'],
        'player': ['updatePresence', 'sendReport', 'getAttributes', 'getFriends', 'getCertificates',
                   'updateAttributes', 'updateFriends', 'getBlocklist'],
        'profiles': ['getManyByName', 'getByName'],
        'telemetry': ['sendEvents'],
    }
    services = {name: {'endpoints': {endpoint: {'uri': origin + '/' + endpoint} for endpoint in endpoints}}
                for name, endpoints in groups.items()}
    services['profiles']['endpoints']['getTexture'] = {'validUris': [origin + '/texture/{textureId}']}
    return {'environment': 'transport-test', 'product': 'minecraft', 'discovery': services}


class SessionAuthority:
    """Accept a single synthetic account and require identical client and server session digests."""

    def __init__(self):
        """Bind an ephemeral loopback port and keep all session state inside this disposable process."""
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), SessionHandler)
        self.server.fixture = self
        self.origin = 'http://127.0.0.1:' + str(self.server.server_port)
        self.url = self.origin + '/discovery'
        self.sessions = set()
        self.accepted = []
        self.rejected = []
        self.lock = threading.Lock()
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        """Start serving before either isolated game process resolves authentication endpoints."""
        self.worker.start()
        return self

    def __exit__(self, exception_type, exception, traceback):
        """Close the HTTP listener and join its worker after success or any game-test failure."""
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=10)
        assert not self.worker.is_alive(), 'Session fixture did not stop'

    def join(self, body):
        """Record only the fixture credential, identity, and bounded native session digest."""
        valid = (body.get('accessToken') == '0' and body.get('selectedProfile') == PLAYER_ID
                 and isinstance(body.get('serverId'), str)
                 and re.fullmatch(r'-?[0-9a-f]{1,40}', body['serverId']))
        if not valid:
            return 403, None
        with self.lock:
            self.sessions.add(body['serverId'])
        return 204, None

    def verify(self, query):
        """Reject unmatched sessions and expose the synthetic profile only after the client registered that digest."""
        name = query.get('username', [''])[0]
        digest = query.get('serverId', [''])[0]
        with self.lock:
            if name == 'EncryptionProbe':
                self.rejected.append(digest)
                return 204, None
            if name != 'TransportProbe' or digest not in self.sessions:
                return 204, None
            self.accepted.append(digest)
        return 200, {'id': PLAYER_ID, 'name': 'TransportProbe', 'properties': []}

    def check_complete(self):
        """Require one accepted real-client exchange and one rejected raw-probe session, with no nonce bypass."""
        assert len(self.sessions) == 1 and len(self.accepted) == 1, 'Missing native session digest agreement'
        assert len(self.rejected) == 1, 'Invalid nonce reached session verification or rejection probe did not'


class SessionHandler(BaseHTTPRequestHandler):
    """Serve only the bounded endpoints needed by isolated Minecraft authentication tests."""

    def setup(self):
        """Bound incomplete HTTP reads so a failed client cannot retain a fixture connection indefinitely."""
        super().setup()
        self.connection.settimeout(10)

    def log_message(self, format_string, *arguments):
        """Keep HTTP request values out of logs because the test must never print authentication fields."""

    def reply(self, status, body=None):
        """Write one complete JSON response or an explicitly empty session-service acknowledgement."""
        content = json.dumps(body).encode('utf-8') if body is not None else b''
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def do_GET(self):
        """Return fixture discovery and keys, or evaluate the native server's session-verification request."""
        request = urlsplit(self.path)
        fixture = self.server.fixture
        if request.path == '/discovery':
            self.reply(200, discovery(fixture.origin))
        elif request.path == '/getPublicKeys':
            self.reply(200, {'profilePropertyKeys': [], 'playerCertificateKeys': []})
        elif request.path == '/verify':
            self.reply(*fixture.verify(parse_qs(request.query)))
        else:
            self.reply(404)

    def do_POST(self):
        """Reject unexpected endpoints and oversized or malformed credentials before recording a synthetic join."""
        if self.path != '/join':
            self.reply(404)
            return
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 4096:
                self.reply(413)
                return
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                self.reply(400)
                return
        except (ValueError, UnicodeDecodeError):
            self.reply(400)
            return
        self.reply(*self.server.fixture.join(body))
