"""Require vanilla ban reasons to arrive before RakNet disconnect, including deterministic datagram loss."""

import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import queue
import socket
import struct
import subprocess
import uuid


def smoke_module():
    """Reuse isolated Fabric runtime setup, readiness checks and owned-process cleanup."""
    spec = importlib.util.spec_from_file_location('server_smoke', Path(__file__).with_name('server-smoke.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def configure(folder, port):
    """Ban the synthetic offline identity using vanilla's persisted ban list, without replacing admission code."""
    (folder / 'eula.txt').write_text('eula=true\n')
    (folder / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\n'
        'network-compression-threshold=-1\nview-distance=2\nsimulation-distance=2\nspawn-chunk-radius=0\n'
        'level-type=minecraft:flat\nmax-players=2\nuse-native-transport=false\n')
    identity = uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:DisconnectProbe').digest(), version=3)
    reason = 'Transport rejection fixture ' + 'x' * 4096 + ' END_OF_REASON'
    (folder / 'banned-players.json').write_text(json.dumps([{
        'uuid': str(identity), 'name': 'DisconnectProbe', 'created': '2020-01-01 00:00:00 +0000',
        'source': 'Local transport test', 'expires': 'forever', 'reason': reason}]))


def tcp_reason(port, protocol, smoke):
    """Establish that vanilla itself produces the full rejection text before testing its RakNet transport."""
    with socket.create_connection(('127.0.0.1', port), timeout=10) as connection:
        connection.settimeout(10)
        host = b'localhost'
        handshake = b'\0' + smoke.varint(protocol) + smoke.varint(len(host)) + host + struct.pack('>H', port) + b'\2'
        name = b'DisconnectProbe'
        optional = b'\1' if protocol < 764 else b''
        hello = b'\0' + smoke.varint(len(name)) + name + optional + bytes.fromhex('12345678123456781234567812345678')
        connection.sendall(smoke.varint(len(handshake)) + handshake + smoke.varint(len(hello)) + hello)
        stream = connection.makefile('rb')
        size = smoke.read_varint(stream)
        assert 0 < size < 1048576, size
        packet = io.BytesIO(stream.read(size))
        assert smoke.read_varint(packet) == 0, 'Vanilla did not reject the banned identity'
        reason = packet.read(smoke.read_varint(packet)).decode('utf-8')
        assert 'Transport rejection fixture' in reason and 'END_OF_REASON' in reason
        assert packet.read() == b''


def raknet_reason(args, java, protocol, mode):
    """Require the complete native reason, proving that the impaired case actually dropped its first fragment."""
    classpath = os.pathsep.join(str(args.probe_dir.resolve() / part) for part in ('classes', 'lib/*'))
    result = subprocess.run([java, '-cp', classpath, 'com.ishland.raknetify.common.connection.RaknetProxyProbe',
                             str(args.port), str(protocol), mode], capture_output=True, text=True, timeout=30)
    print(result.stdout, end='', flush=True)
    assert result.returncode == 0, result.stdout + result.stderr
    assert 'BAN_REASON_OK' in result.stdout
    if mode == 'ban-drop':
        assert 'DROPPED_FIRST_REASON_DATAGRAM' in result.stdout, 'Loss witness did not exercise the fragmented reason'


def run(args, version, smoke):
    """Compare TCP, ordinary RakNet and impaired RakNet against the same real banned-player decision."""
    folder = smoke.prepare_server(args, version, {}, {}, {})
    candidate = folder / 'mods' / args.jar.name
    candidate.replace(folder / 'mods/raknetify2.jar')
    configure(folder, args.port)
    events = queue.Queue()
    log = folder / 'disconnect.log'
    java = args.java_modern if version.startswith('26.') else smoke.select_java(args, version)
    process, reader = smoke.start_server(folder, log, events, java)
    try:
        smoke.await_ready(process, events)
        metadata = smoke.server_metadata(folder, version)
        smoke.check_tcp_when_ready(args.port, metadata)
        tcp_reason(args.port, metadata['protocol_version'], smoke)
        for mode in ('ban', 'ban-drop'):
            raknet_reason(args, java, metadata['protocol_version'], mode)
        process.stdin.write('stop\n')
        process.stdin.flush()
        assert process.wait(timeout=45) == 0
        reader.join(timeout=10)
        text = log.read_text(encoding='utf-8')
        assert 'joined the game' not in text, 'Banned fixture identity was admitted'
        smoke.check_log(log, version)
        print('PASS', version, 'native ban reasons over TCP and RakNet with and without datagram loss', flush=True)
    finally:
        smoke.stop_server(process, reader)
    if args.discard_runtime:
        smoke.discard_runtime(folder)


def arguments():
    """Accept portable runtimes and artifacts without reading any installed profile or account store."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--probe-dir', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--java-17', default='java')
    parser.add_argument('--java-21', default='java')
    parser.add_argument('--java-modern', default='java')
    parser.add_argument('--versions', nargs='+', default=['1.19.3', '1.21.11', '26.3', '26.4-snapshot-7'])
    parser.add_argument('--port', type=int, default=25894)
    parser.add_argument('--discard-runtime', action='store_true')
    parser.set_defaults(encryption_legacy=None, encryption_modern=None)
    return parser.parse_args()


def main():
    """Run the issue's original Minecraft generation and the current packet/runtime boundaries."""
    args = arguments()
    smoke = smoke_module()
    for version in args.versions:
        run(args, version, smoke)


if __name__ == '__main__':
    main()
