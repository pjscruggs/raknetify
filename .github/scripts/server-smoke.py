"""Verify isolated Fabric server startup and Minecraft status over TCP and connected RakNet.

Each version runs with native transport enabled and disabled. These checks establish
listener and pipeline health, not a complete authenticated Minecraft play session.
Pinned Polymer runs also check resource-pack downloads over shared-port HTTP.
The optional encryption fixture checks native key exchange without admitting a player.
Every child server is stopped before the next transport or version uses the port.
"""

import argparse
import importlib.util
import hashlib
import io
import json
import os
import queue
import re
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
import zipfile
from pathlib import Path
import shutil


MAGIC = bytes.fromhex('00ffff00fefefefefdfdfdfd12345678')
"""RakNet offline-message marker used by the connection-request exchange."""

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


def check_tcp(port, metadata, proxy_protocol=False):
    """Request local TCP status and verify the advertised Minecraft name and protocol."""
    host = b'127.0.0.1'
    handshake = b'\x00' + varint(metadata['protocol_version']) + varint(len(host)) + host + struct.pack('>H', port) + b'\x01'
    with socket.create_connection(('127.0.0.1', port), timeout=10) as connection:
        if proxy_protocol:
            connection.sendall(b'PROXY TCP4 192.0.2.50 198.51.100.8 12345 25565\r\n')
        connection.sendall(varint(len(handshake)) + handshake + b'\x01\x00')
        stream = connection.makefile('rb')
        length = read_varint(stream)
        assert 0 < length < 1048576, length
        assert read_varint(stream) == 0
        size = read_varint(stream)
        assert 0 < size < length
        status = json.loads(stream.read(size))
        assert status['version']['name'] == metadata['name'], status
        assert status['version']['protocol'] == metadata['protocol_version'], status


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


def check_connected_udp(port, metadata, java, probe_dir):
    """Exercise Minecraft's application pipeline over the original single-channel RakNet wire format."""
    classpath = os.pathsep.join(str(probe_dir.resolve() / part) for part in ('classes', 'lib/*'))
    output = subprocess.check_output(
        [java, '-cp', classpath, 'com.ishland.raknetify.common.connection.RaknetStatusProbe',
         str(port), str(metadata['protocol_version'])], text=True, encoding='utf-8', timeout=30)
    status = json.loads(next(line for line in output.splitlines() if line.startswith('{')))
    assert status['version']['protocol'] == metadata['protocol_version'], status
    assert status['version']['name'] == metadata['name'], status


def install_mods(folder, dependencies):
    """Download only the locked artifacts and reject changed bytes before installing a test dependency."""
    for dependency in dependencies:
        with urllib.request.urlopen(dependency['url'], timeout=60) as response:
            data = response.read()
        assert hashlib.sha512(data).hexdigest() == dependency['sha512'], dependency['filename']
        (folder / 'mods' / dependency['filename']).write_bytes(data)


def prepare_packetfixer(folder, dependencies):
    """Install pinned PacketFixer and explicitly raise strings, which older releases keep at vanilla size."""
    install_mods(folder, dependencies)
    config = folder / 'config'
    config.mkdir(parents=True, exist_ok=True)
    (config / 'packetfixer.properties').write_text('allSizesUnlimited=true\nstringSize=65536\n')


def prepare_polymer(folder, dependencies):
    """Install hash-pinned matching mods and enable Polymer's HTTP hosting on the Minecraft TCP port."""
    install_mods(folder, dependencies)
    config = folder / 'config' / 'polymer'
    config.mkdir(parents=True, exist_ok=True)
    (config / 'auto-host.json').write_text(json.dumps({'enabled': True, 'type': 'polymer:netty'}))


def check_polymer_http(port):
    """Download the generated resource pack over TCP while RakNet uses UDP on the same port."""
    url = f'http://127.0.0.1:{port}/eu.pb4.polymer.autohost/polymer/resources+pack.zip'
    for attempt in range(60):
        try:
            with urllib.request.urlopen(url, timeout=10) as response:
                assert response.headers.get('Server') == 'polymer-autohost'
                with zipfile.ZipFile(io.BytesIO(response.read())) as pack:
                    assert 'pack.mcmeta' in pack.namelist()
            return
        except urllib.error.HTTPError as error:
            if error.code != 403 or attempt == 59:
                raise
            time.sleep(1)


def start_server(folder, log, events, java, encryption=False, packetfixer=False, transfers=False):
    """Start one isolated server and return its process and output-reader thread.

    Output is flushed to the log and copied into the readiness queue. The caller owns
    process shutdown and must join the reader before inspecting the complete log.
    """
    test_flags = ['-Draknetify.test.encryption=true'] if encryption else []
    if packetfixer:
        test_flags.append('-Draknetify.test.packetLimits=true')
    if transfers:
        test_flags.append('-Draknetify.test.transfers=true')
    process = subprocess.Popen([java, '-Draknetify.saveChannelMappings=true', *test_flags, '-Xms256M', '-Xmx1G', '-jar', 'fabric-server-launch.jar', 'nogui'],
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


def check_tcp_when_ready(port, metadata, proxy_protocol=False):
    """Retry transient socket readiness failures without hiding incorrect status responses."""
    for attempt in range(10):
        try:
            check_tcp(port, metadata, proxy_protocol)
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


def check_log(log, version, krypton=False, packetfixer=False):
    """Require the correct nested runtime and reject known mixin, linkage, and buffer-leak failures."""
    text = log.read_text()
    runtime = 'modern' if version.startswith('26.') else 'legacy'
    assert re.search(r'\braknetify_runtime\s+\S+\.' + runtime + r'\b', text), runtime
    for mod, enabled in (('krypton', krypton), ('packetfixer', packetfixer)):
        if enabled:
            assert re.search(r'\b' + mod + r'\s+\S+', text), mod + ' was not loaded'
    for failure in ('Critical injection failure', 'Mixin apply failed', 'NoClassDefFoundError', 'LEAK:'):
        assert failure not in text, failure


def server_metadata(folder, version):
    """Read the expected protocol and display name from Mojang's downloaded server metadata."""
    with zipfile.ZipFile(folder / '.fabric' / 'server' / f'{version}-server.jar') as server:
        metadata = json.loads(server.read('version.json'))
    assert metadata['id'] == version, metadata
    return metadata


def check_mappings(folder, metadata):
    """Require native packet-ID routes to match the tables shipped to proxy users."""
    protocol = str(metadata['protocol_version'])
    generated = json.loads((folder / 'channelMappings.json').read_text())['mappings'][protocol]
    expected = json.loads((folder / 'expected-mappings.json').read_text())['mappings'][protocol]
    assert generated == expected, 'Native and bundled proxy routes differ for ' + protocol


def bundled_mappings(jar):
    """Read both universal components and reject inconsistent bundled proxy routing tables."""
    with zipfile.ZipFile(jar) as archive:
        tables = []
        for component in ('legacy', 'modern'):
            with zipfile.ZipFile(io.BytesIO(archive.read('META-INF/jars/' + component + '.jar'))) as nested:
                tables.append(json.loads(nested.read('raknetify-channel-mappings.json')))
    assert tables[0] == tables[1], 'Universal components contain different proxy routes'
    return tables[0]


def check_server(folder, version, native, java, probe_dir, polymer=False, krypton=False, encryption=False, packetfixer=False, port=25871, proxy_protocol=False):
    """Run one transport configuration through startup, network probes, and clean shutdown.

    The supplied folder is an isolated smoke-test installation. Its properties and
    EULA acceptance are written here, and native and NIO runs use different worlds.
    """
    (folder / 'eula.txt').write_text('eula=true\n')
    (folder / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nenforce-secure-profile=false\n'
        'online-mode=' + str(encryption).lower() + '\n'
        'view-distance=2\nsimulation-distance=2\nspawn-chunk-radius=0\n'
        'level-type=minecraft:flat\nmax-players=2\n'
        'use-native-transport=' + str(native).lower() + '\nlevel-name=world-' + str(native).lower() + '\n')
    proxy = proxy_fixture()
    proxy.configure(folder, proxy_protocol)
    log = folder / ('native.log' if native else 'nio.log')
    events = queue.Queue()
    process, reader = start_server(folder, log, events, java, encryption, packetfixer)
    try:
        await_ready(process, events)
        metadata = server_metadata(folder, version)
        check_mappings(folder, metadata)
        check_tcp_when_ready(port, metadata)
        with proxy.transport(proxy_protocol, port, repeated=not native) as frontend:
            check_udp(frontend)
            check_connected_udp(frontend, metadata, java, probe_dir)
            if encryption:
                check_encryption(frontend, metadata, java, probe_dir, packetfixer)
            if proxy_protocol:
                proxy.check_ip_ban(process, folder, java, probe_dir, frontend, metadata['protocol_version'])
        if polymer:
            check_polymer_http(port)
        java_path = Path(shutil.which(java) or java).resolve()
        jcmd = java_path.with_name('jcmd.exe' if os.name == 'nt' else 'jcmd')
        dump = subprocess.check_output([str(jcmd), str(process.pid), 'Thread.print'], text=True)
        (folder / ('native-threads.log' if native else 'nio-threads.log')).write_text(dump)
        transport = 'Epoll' if native and sys.platform.startswith('linux') else 'NIO'
        assert 'Netty Server ' + transport + ' Raknetify' in dump, transport
        process.stdin.write('stop\n')
        process.stdin.flush()
        assert process.wait(timeout=45) == 0
        reader.join(timeout=10)
        check_log(log, version, krypton, packetfixer)
        report_success(version, transport, log, polymer, krypton, encryption, packetfixer)
    finally:
        stop_server(process, reader)


def proxy_fixture():
    """Load the loopback forwarding fixture without importing its helpers into production code."""
    spec = importlib.util.spec_from_file_location('udp_proxy_fixture', Path(__file__).with_name('udp-proxy-fixture.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def report_success(version, transport, log, polymer, krypton, encryption, packetfixer):
    """Report only the checks selected for this run, including the server-side encryption witness."""
    checks = 'startup, native/proxy routing parity, TCP and connected RakNet status, shutdown'
    if polymer:
        checks += ', Polymer HTTP resource-pack download'
    if krypton:
        checks += ', Krypton loaded'
    if packetfixer:
        assert 'PASS native compression above eight MiB' in log.read_text()
        assert 'PASS native frame helper ownership' in log.read_text()
        assert 'PASS native string above 32767 characters' in log.read_text()
        checks += ', PacketFixer native size limits'
    if encryption:
        assert 'PASS native encrypted challenge exchange' in log.read_text()
        checks += ', native RSA and bidirectional encryption'
    print('PASS', version, transport, checks, flush=True)


def check_encryption(port, metadata, java, probe_dir, packetfixer):
    """Require the native key exchange and encrypted challenge in both directions without account lookup."""
    classpath = os.pathsep.join(str(probe_dir.resolve() / part) for part in ('classes', 'lib/*'))
    output = subprocess.check_output(
        [java, '-cp', classpath, 'com.ishland.raknetify.common.connection.RaknetEncryptionProbe',
         str(port), str(metadata['protocol_version']), str(8 * 1024 * 1024 + 64 if packetfixer else 8)],
        text=True, timeout=45)
    assert 'PASS native RSA and bidirectional RakNet encryption' in output, output


def encryption_fixture(args, version):
    """Select the optional isolated fixture for the native namespace, never a distributed mod artifact."""
    return args.encryption_modern if version.startswith('26.') else args.encryption_legacy


def arguments():
    """Parse runtime locations and optional dependency locks without choosing unadvertised mod versions."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--java-17', default='java')
    parser.add_argument('--java-21', default='java')
    parser.add_argument('--probe-dir', type=Path, default=Path('common/build/transport-probe'))
    parser.add_argument('--polymer-lock', type=Path)
    parser.add_argument('--krypton-lock', type=Path)
    parser.add_argument('--packetfixer-lock', type=Path)
    parser.add_argument('--versions', nargs='+')
    parser.add_argument('--shard', type=int, default=1)
    parser.add_argument('--shards', type=int, default=1)
    parser.add_argument('--port', type=int, default=25871)
    parser.add_argument('--discard-runtime', action='store_true')
    parser.add_argument('--proxy-protocol', action='store_true')
    parser.add_argument('--encryption-legacy', type=Path)
    parser.add_argument('--encryption-modern', type=Path)
    return parser.parse_args()


def prepare_server(args, version, polymer, krypton, packetfixer):
    """Prepare an isolated runtime with only the explicitly selected pinned dependency sets."""
    folder = args.work_dir / version
    (folder / 'mods').mkdir(parents=True, exist_ok=True)
    shutil.copy2(args.jar, folder / 'mods' / args.jar.name)
    (folder / 'expected-mappings.json').write_text(json.dumps(bundled_mappings(args.jar)))
    (folder / 'channelMappings.json').unlink(missing_ok=True)
    if polymer:
        prepare_polymer(folder, polymer[version])
    if krypton:
        install_mods(folder, krypton[version])
    if packetfixer:
        prepare_packetfixer(folder, packetfixer[version])
    fixture = encryption_fixture(args, version)
    if fixture:
        shutil.copy2(fixture, folder / 'mods' / 'encryption-test.jar')
    launcher = folder / 'fabric-server-launch.jar'
    if not launcher.exists():
        url = f'https://meta.fabricmc.net/v2/versions/loader/{version}/0.20.1/1.1.1/server/jar'
        with urllib.request.urlopen(url, timeout=60) as response:
            launcher.write_bytes(response.read())
    return folder


def select_java(args, version):
    """Select the supported Java generation without embedding machine-specific installation paths."""
    if version.startswith('26.'):
        return 'java'
    if tuple(int(part) for part in version.split('.')) >= (1, 20, 5):
        return args.java_21
    return args.java_17


def discard_runtime(folder):
    """Remove regenerable server libraries after both runs pass, keeping worlds, mods and diagnostic logs."""
    root = folder.resolve()
    for name in ('.fabric', 'libraries', 'versions'):
        target = folder / name
        if target.is_symlink() or target.resolve().parent != root:
            raise ValueError('Runtime cache is outside the isolated server folder')
        if target.is_dir():
            shutil.rmtree(target)


def shard_versions(versions, shard, shards):
    """Partition the complete selected version list without omissions, duplicates, empty jobs, or order changes within a job."""
    if len(versions) != len(set(versions)):
        raise ValueError('Duplicate Minecraft version in the selected test list')
    if not 1 <= shard <= shards <= len(versions):
        raise ValueError('Invalid or empty Minecraft test shard')
    return versions[shard - 1::shards]


def main():
    """Check both native and NIO transport settings for each selected or locked Minecraft version."""
    args = arguments()
    polymer = json.loads(args.polymer_lock.read_text()) if args.polymer_lock else {}
    krypton = json.loads(args.krypton_lock.read_text()) if args.krypton_lock else {}
    packetfixer = json.loads(args.packetfixer_lock.read_text()) if args.packetfixer_lock else {}
    supported = json.loads((Path(__file__).resolve().parents[2] / 'supported-versions.json').read_text())
    versions = args.versions or list(packetfixer) or list(krypton) or ['1.18.2', '1.21.1'] + supported['modern']['gameVersions']
    for version in shard_versions(versions, args.shard, args.shards):
        folder = prepare_server(args, version, polymer, krypton, packetfixer)
        java = select_java(args, version)
        for native in (True, False):
            check_server(folder, version, native, java, args.probe_dir, bool(polymer), bool(krypton), bool(encryption_fixture(args, version)), bool(packetfixer), args.port, args.proxy_protocol)
        if args.discard_runtime:
            discard_runtime(folder)


if __name__ == '__main__':
    main()
