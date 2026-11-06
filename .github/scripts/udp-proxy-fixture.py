"""Relay loopback UDP through independently encoded PROXY v2 headers for packaged server tests."""
from contextlib import contextmanager
import hashlib
import ipaddress
import json
import os
import selectors
import socket
import struct
import subprocess
import threading
import time
import uuid


POLICY = {'trustedProxies': ['127.0.0.1']}
"""Exact fixture trust list, never a wildcard or a real external proxy address."""


def configure(folder, enabled):
    """Install or remove only this fixture's exact policy before starting an isolated server."""
    path = folder / 'config/raknetify2-proxy-protocol.json'
    if path.exists():
        assert json.loads(path.read_text(encoding='utf-8')) == POLICY, 'Refusing to replace another proxy policy'
    if enabled:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(POLICY), encoding='utf-8')
        allow_probe(folder)
    elif path.exists():
        path.unlink()


def allow_probe(folder):
    """Whitelist the synthetic offline identity so an IP-ban test reaches the native IP check on modern servers."""
    path = folder / 'whitelist.json'
    entries = json.loads(path.read_text(encoding='utf-8')) if path.exists() else []
    if not any(entry['name'] == 'DisconnectProbe' for entry in entries):
        identity = uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:DisconnectProbe').digest(), version=3)
        entries.append({'uuid': str(identity), 'name': 'DisconnectProbe'})
        path.write_text(json.dumps(entries), encoding='utf-8')


def header(client, destination):
    """Encode UDP4 or UDP6 addresses directly from the protocol specification, independent of Java's decoder."""
    source = ipaddress.ip_address(client[0])
    target = ipaddress.ip_address(destination[0])
    assert source.version == target.version
    addresses = source.packed + target.packed + struct.pack('>HH', client[1], destination[1])
    return b'\r\n\r\n\x00\r\nQUIT\n' + struct.pack('>BBH', 0x21, 0x12 if source.version == 4 else 0x22,
                                                     len(addresses)) + addresses


class UdpProxy:
    """One selector thread gives every client a separate connected backend socket, as real UDP proxies do."""

    def __init__(self, port, repeated=False, identity='192.0.2.50', duplicate=False):
        """Bind a disposable frontend and retain only bounded, loopback fixture resources."""
        self.target = ('127.0.0.1', port)
        self.repeated = repeated
        self.identity = identity
        self.duplicate = duplicate
        self.duplicates = 0
        self.selector = selectors.DefaultSelector()
        self.front = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.front.bind(('127.0.0.1', 0))
        self.front.setblocking(False)
        self.selector.register(self.front, selectors.EVENT_READ, None)
        self.clients = {}
        self.stopped = threading.Event()
        self.failure = None
        self.thread = threading.Thread(target=self.run, name='udp-proxy-fixture')

    def __enter__(self):
        """Start forwarding only after the caller has configured and started its owned backend."""
        self.thread.start()
        return self.front.getsockname()[1]

    def __exit__(self, kind, value, traceback):
        """Join the worker, close every socket and retain a worker failure alongside any foreground failure."""
        self.stopped.set()
        self.thread.join(timeout=5)
        assert not self.thread.is_alive(), 'UDP proxy fixture failed to stop'
        for channel in self.clients.values():
            channel.close()
        self.front.close()
        self.selector.close()
        if self.failure is not None:
            raise self.failure from value

    def run(self):
        """Dispatch datagrams with a short selector deadline so cleanup never waits on an idle socket."""
        try:
            while not self.stopped.is_set():
                for key, _ in self.selector.select(0.1):
                    self.dispatch(key)
        except Exception as failure:
            self.failure = failure

    def dispatch(self, key):
        """Preserve unmodified downstream packets and prepend metadata on the selected upstream packets."""
        try:
            data, sender = key.fileobj.recvfrom(65535)
        except ConnectionResetError:
            # Windows reports late ICMP errors from finished probes on this shared frontend.
            # Keep serving other clients, but preserve backend errors as test failures.
            if key.data is not None:
                raise
            return
        if key.data is not None:
            for _ in range(self.copies(data)):
                self.front.sendto(data, key.data)
            return
        copies = self.copies(data)
        first = sender not in self.clients
        channel = self.backend(sender)
        if first or self.repeated:
            destination = '198.51.100.8' if ':' not in self.identity else '2001:db8::8'
            data = header((self.identity, sender[1]), (destination, self.target[1])) + data
        for _ in range(copies):
            channel.send(data)

    def copies(self, data):
        """Duplicate framed RakNet datagrams in both directions while leaving discovery and ACK traffic unchanged."""
        if self.duplicate and data and data[0] & 0xf0 == 0x80:
            self.duplicates += 1
            return 2
        return 1

    def backend(self, sender):
        """Allocate at most 64 backend tuples and keep each alive for exactly this bounded test run."""
        if sender not in self.clients:
            assert len(self.clients) < 64, 'Unexpected client count in UDP fixture'
            channel = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            channel.bind(('127.0.0.1', 0))
            channel.connect(self.target)
            channel.setblocking(False)
            self.selector.register(channel, selectors.EVENT_READ, sender)
            self.clients[sender] = channel
        return self.clients[sender]


def reject_direct(port):
    """Require an enabled backend to drop an ordinary RakNet handshake that has no proxy identity."""
    magic = bytes.fromhex('00ffff00fefefefefdfdfdfd12345678')
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as channel:
        channel.settimeout(0.5)
        channel.sendto((b'\x05' + magic + b'\x09').ljust(1200, b'\x00'), ('127.0.0.1', port))
        try:
            channel.recv(8192)
        except socket.timeout:
            return
    raise AssertionError('Required PROXY metadata was bypassed by a direct UDP client')


def check_ip_ban(process, folder, java, probe_dir, port, protocol):
    """Require vanilla's IP-ban check to use the forwarded address, not the trusted loopback relay address."""
    process.stdin.write('ban-ip 192.0.2.50 Transport rejection fixture END_OF_REASON\n')
    process.stdin.flush()
    try:
        await_ban(folder / 'banned-ips.json', True)
        classpath = os.pathsep.join(str(probe_dir.resolve() / part) for part in ('classes', 'lib/*'))
        output = subprocess.check_output([java, '-cp', classpath,
                                          'com.ishland.raknetify.common.connection.RaknetProxyProbe',
                                          str(port), str(protocol), 'ban'], text=True, encoding='utf-8', timeout=40)
        assert 'BAN_REASON_OK' in output, output
        print('PASS forwarded client IP ban', flush=True)
    finally:
        process.stdin.write('pardon-ip 192.0.2.50\n')
        process.stdin.flush()
        await_ban(folder / 'banned-ips.json', False)


def await_ban(path, present):
    """Wait for vanilla to persist its admission rule before probing or reusing the owned test world."""
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        try:
            entries = json.loads(path.read_text(encoding='utf-8'))
        except (FileNotFoundError, json.JSONDecodeError):
            time.sleep(0.05)
            continue
        if any(entry['ip'] == '192.0.2.50' for entry in entries) == present:
            return
        time.sleep(0.05)
    raise AssertionError('Native IP ban update was not persisted')


@contextmanager
def transport(enabled, port, repeated=False, duplicate=False):
    """Select a real relay for proxy checks and verify direct access is rejected after backend readiness is proven."""
    if not enabled:
        yield port
        return
    proxy = UdpProxy(port, repeated, duplicate=duplicate)
    with proxy as frontend:
        yield frontend
        reject_direct(port)
        if duplicate:
            assert proxy.duplicates > 0, 'No framed traffic crossed the duplicate-injection relay'
            print('PASS duplicated framed UDP datagrams', proxy.duplicates, flush=True)
