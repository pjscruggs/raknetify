"""Verify isolated Fabric server startup, TCP status, and the initial RakNet UDP handshake.

Each version runs with native transport enabled and disabled. These checks establish
listener and pipeline health, not a complete authenticated Minecraft play session.
Every child server is stopped before the next transport or version uses the port.
"""

import argparse
import json
import queue
import re
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path
import shutil


MAGIC = bytes.fromhex('00ffff00fefefefefdfdfdfd12345678')
"""RakNet offline-message marker used by the connection-request exchange."""

PROTOCOLS = {'1.18.2': 758, '1.21.1': 767, '26.1': 775, '26.1.1': 775, '26.1.2': 775, '26.2': 776, '26.3': 777,
             '26.4-snapshot-7': 1073742169}
"""Expected wire protocol numbers for the server versions in the smoke matrix."""


def varint(value):
    """Encode a signed 32-bit Minecraft integer using its unsigned two's-complement bits."""
    value &= 0xffffffff
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result + bytes([value]))


def read_varint(stream):
    """Consume a VarInt, rejecting premature EOF and encodings longer than five bytes."""
    value = 0
    for shift in range(0, 35, 7):
        part = stream.read(1)
        if not part:
            raise EOFError('Incomplete VarInt')
        value |= (part[0] & 127) << shift
        if part[0] < 128:
            return value
    raise ValueError('VarInt exceeds five bytes')


def check_tcp(port, version):
    """Request local TCP status and verify the advertised Minecraft name and protocol."""
    host = b'127.0.0.1'
    handshake = b'\x00' + varint(PROTOCOLS[version]) + varint(len(host)) + host + struct.pack('>H', port) + b'\x01'
    with socket.create_connection(('127.0.0.1', port), timeout=10) as connection:
        connection.sendall(varint(len(handshake)) + handshake + b'\x01\x00')
        stream = connection.makefile('rb')
        length = read_varint(stream)
        assert 0 < length < 1048576, length
        assert read_varint(stream) == 0
        size = read_varint(stream)
        assert 0 < size < length
        status = json.loads(stream.read(size))
        assert status['version']['name'] == version.replace('-snapshot-', ' Snapshot '), status
        assert status['version']['protocol'] == PROTOCOLS[version], status


def check_udp(port):
    """Verify an offline RakNet handshake response and its negotiated MTU bounds."""
    # Open Connection Request 1 negotiates the RakNet transport before Minecraft login.
    request = (b'\x05' + MAGIC + b'\x09').ljust(1200, b'\x00')
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
        connection.settimeout(10)
        connection.connect(('127.0.0.1', port))
        connection.send(request)
        reply = connection.recv(8192)
        assert len(reply) == 28 and reply[0] == 6, reply.hex()
        assert reply[1:17] == MAGIC and reply[25] == 0, reply.hex()
        assert 128 <= struct.unpack('>H', reply[26:28])[0] <= 1200


def start_server(folder, log, events, java):
    """Start one isolated server and return its process and output-reader thread.

    Output is flushed to the log and copied into the readiness queue. The caller owns
    process shutdown and must join the reader before inspecting the complete log.
    """
    process = subprocess.Popen([java, '-Xms256M', '-Xmx1G', '-jar', 'fabric-server-launch.jar', 'nogui'],
                               cwd=folder, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')

    def collect():
        """Drain server output to disk and publish each line for readiness detection."""
        with log.open('w', encoding='utf-8') as output:
            for line in process.stdout:
                output.write(line)
                output.flush()
                events.put(line)

    reader = threading.Thread(target=collect)
    reader.start()
    return process, reader


def await_ready(process, events):
    """Wait up to three minutes for the normal startup marker, failing on early exit."""
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Server exited before readiness: ' + str(process.returncode))
        try:
            if 'Done (' in events.get(timeout=1):
                return
        except queue.Empty:
            pass
    raise TimeoutError('Server startup exceeded 180 seconds')


def check_tcp_when_ready(port, version):
    """Retry transient socket readiness failures without hiding incorrect status responses."""
    for attempt in range(10):
        try:
            check_tcp(port, version)
            return
        except (EOFError, OSError):
            if attempt == 9:
                raise
            time.sleep(0.5)


def stop_server(process, reader):
    """Stop the owned server, force termination after a timeout, and join its log reader."""
    if process.poll() is None:
        try:
            process.stdin.write('stop\n')
            process.stdin.flush()
            process.wait(timeout=45)
        except (OSError, subprocess.TimeoutExpired):
            process.kill()
            process.wait(timeout=10)
    reader.join(timeout=10)


def check_log(log, version):
    """Require the correct nested runtime and reject known mixin, linkage, and buffer-leak failures."""
    text = log.read_text()
    runtime = 'modern' if version.startswith('26.') else 'legacy'
    assert re.search(r'\braknetify_runtime\s+\S+\.' + runtime + r'\b', text), runtime
    for failure in ('Critical injection failure', 'Mixin apply failed', 'NoClassDefFoundError', 'LEAK:'):
        assert failure not in text, failure


def check_server(folder, version, native, java):
    """Run one transport configuration through startup, network probes, and clean shutdown.

    The supplied folder is an isolated smoke-test installation. Its properties and
    EULA acceptance are written here, and native and NIO runs use different worlds.
    """
    port = 25871
    (folder / 'eula.txt').write_text('eula=true\n')
    (folder / 'server.properties').write_text(
        'server-ip=127.0.0.1\nserver-port=25871\nonline-mode=false\n'
        'view-distance=2\nsimulation-distance=2\nspawn-chunk-radius=0\n'
        'level-type=minecraft:flat\nmax-players=2\n'
        'use-native-transport=' + str(native).lower() + '\nlevel-name=world-' + str(native).lower() + '\n')
    log = folder / ('native.log' if native else 'nio.log')
    events = queue.Queue()
    process, reader = start_server(folder, log, events, java)
    try:
        await_ready(process, events)
        check_tcp_when_ready(port, version)
        check_udp(port)
        dump = subprocess.check_output([str(Path(java).with_name('jcmd')), str(process.pid), 'Thread.print'], text=True)
        (folder / ('native-threads.log' if native else 'nio-threads.log')).write_text(dump)
        transport = 'Epoll' if native and sys.platform.startswith('linux') else 'NIO'
        assert 'Netty Server ' + transport + ' Raknetify' in dump, transport
        process.stdin.write('stop\n')
        process.stdin.flush()
        assert process.wait(timeout=45) == 0
        reader.join(timeout=10)
        check_log(log, version)
        print('PASS', version, transport, 'startup, TCP status, RakNet UDP response, shutdown', flush=True)
    finally:
        stop_server(process, reader)


def main():
    """Prepare the selected Fabric versions and check both native and NIO transport settings."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--java-17', default='java')
    parser.add_argument('--java-21', default='java')
    supported = json.loads((Path(__file__).resolve().parents[2] / 'supported-versions.json').read_text())
    parser.add_argument('--versions', nargs='+', default=['1.18.2', '1.21.1'] + supported['modern']['gameVersions'])
    args = parser.parse_args()
    for version in args.versions:
        folder = args.work_dir / version
        (folder / 'mods').mkdir(parents=True, exist_ok=True)
        shutil.copy2(args.jar, folder / 'mods' / args.jar.name)
        launcher = folder / 'fabric-server-launch.jar'
        if not launcher.exists():
            url = f'https://meta.fabricmc.net/v2/versions/loader/{version}/0.20.1/1.1.1/server/jar'
            with urllib.request.urlopen(url, timeout=60) as response:
                launcher.write_bytes(response.read())
        java = args.java_17 if version == '1.18.2' else args.java_21 if version == '1.21.1' else 'java'
        check_server(folder, version, True, java)
        check_server(folder, version, False, java)


if __name__ == '__main__':
    main()
