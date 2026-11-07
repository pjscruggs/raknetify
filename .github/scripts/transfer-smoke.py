"""Exercise native server capability negotiation over loopback TCP without admitting players."""

import argparse
import importlib.util
import io
import json
from pathlib import Path
import queue
import select
import socket
import struct


def smoke_module():
    """Reuse the bounded server launcher, exact dependency downloads and shutdown cleanup."""
    spec = importlib.util.spec_from_file_location('server_smoke', Path(__file__).with_name('server-smoke.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def receive(stream, smoke):
    """Read a complete login frame and reject sizes outside the isolated query contract."""
    length = smoke.read_varint(stream)
    assert 0 < length <= 1048576, length
    data = bytearray()
    while len(data) < length:
        part = stream.read(length - len(data))
        assert part, 'Incomplete native login frame'
        data.extend(part)
    return io.BytesIO(data)


def send(connection, packet, smoke):
    """Frame one login packet without compression, which the fixture server explicitly disables."""
    connection.sendall(smoke.varint(len(packet)) + packet)


def probe(port, protocol, response, handled, smoke):
    """Answer the real versioned query as a capable or unmodified client and require normal verification to resume."""
    with socket.create_connection(('127.0.0.1', port), timeout=10) as connection:
        connection.settimeout(10)
        host = b'localhost'
        handshake = b'\0' + smoke.varint(protocol) + smoke.varint(len(host)) + host + struct.pack('>H', port) + b'\2'
        send(connection, handshake, smoke)
        send(connection, b'\0\x0dTransferProbe' + bytes.fromhex('12345678123456781234567812345678'), smoke)
        stream = connection.makefile('rb', buffering=0)
        queries = 0
        for _ in range(32):
            packet = receive(stream, smoke)
            packet_id = smoke.read_varint(packet)
            if packet_id == 0:
                reason = packet.read(smoke.read_varint(packet)).decode('utf-8')
                assert queries == 1, 'Verification finished without the owned query'
                assert 'transfer-probe:capable=' + str(handled).lower() in reason, reason
                assert packet.read() == b'', 'Trailing disconnect bytes'
                return
            assert packet_id == 4, 'Unexpected login packet ' + str(packet_id)
            queries += answer(connection, packet, response, smoke)
        raise AssertionError('Too many native login queries')


def answer(connection, packet, response, smoke):
    """Preserve unrelated mods' unhandled queries and withhold the owned reply briefly to check the verification gate."""
    transaction = smoke.read_varint(packet)
    identifier = packet.read(smoke.read_varint(packet)).decode('utf-8')
    owned = identifier == 'raknetify:transfer_v1'
    if owned:
        assert packet.read() == b'', 'Unexpected version-one query payload'
        assert not select.select([connection], [], [], 0.25)[0], 'Login advanced before the capability reply'
    send(connection, b'\2' + smoke.varint(transaction) + (response if owned else b'\0'), smoke)
    return int(owned)


def configure(folder, port):
    """Prepare an isolated offline loopback server whose test mixin stops every login before admission."""
    (folder / 'eula.txt').write_text('eula=true\n')
    (folder / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\n'
        'network-compression-threshold=-1\nview-distance=2\nsimulation-distance=2\nspawn-chunk-radius=0\n'
        'level-type=minecraft:flat\nmax-players=2\nuse-native-transport=false\n')
    config = folder / 'config'
    config.mkdir(exist_ok=True)
    (config / 'raknetify2-transfers.json').write_text(json.dumps({'destinations': [
        {'host': 'fixture.test', 'tcpPort': 25565, 'udpPort': 25570}]}))


def run(args, version, smoke, companions):
    """Run both capability outcomes through actual native packet decoders and always stop the owned server."""
    folder = smoke.prepare_server(args, version, {}, {}, {})
    if version in companions:
        smoke.install_mods(folder, companions[version])
    configure(folder, args.port)
    events = queue.Queue()
    log = folder / 'transfer.log'
    java = args.java_modern if version.startswith('26.') else args.java_21
    process, reader = smoke.start_server(folder, log, events, java, smoke.SmokeFeatures(transfers=True))
    try:
        smoke.await_ready(process, events)
        metadata = smoke.server_metadata(folder, version)
        smoke.check_tcp_when_ready(args.port, metadata)
        for response, handled in ((b'\0', False), (b'\1', True), (b'\1\0', False), (b'\2', False)):
            probe(args.port, metadata['protocol_version'], response, handled, smoke)
        process.stdin.write('stop\n')
        process.stdin.flush()
        assert process.wait(timeout=45) == 0
        reader.join(timeout=10)
        smoke.check_log(log, version)
        for handled in ('false', 'true'):
            assert 'PASS native login transfer-probe:capable=' + handled in log.read_text(encoding='utf-8')
        print('PASS', version, 'native login capability, unsupported/malformed replies, pending verification and disconnect', flush=True)
    finally:
        smoke.stop_server(process, reader)
    if args.discard_runtime:
        smoke.discard_runtime(folder)


def arguments():
    """Select explicit fixture artifacts and portable Java paths without affecting installed game profiles."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--encryption-legacy', type=Path, required=True)
    parser.add_argument('--encryption-modern', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--java-21', default='java')
    parser.add_argument('--java-modern', default='java')
    parser.add_argument('--versions', nargs='+')
    parser.add_argument('--companion-lock', type=Path)
    parser.add_argument('--port', type=int, default=25892)
    parser.add_argument('--discard-runtime', action='store_true')
    return parser.parse_args()


def main():
    """Cover every advertised release with native transfer packets unless the runner selects a subset."""
    args = arguments()
    smoke = smoke_module()
    manifest = json.loads((Path(__file__).resolve().parents[2] / 'supported-versions.json').read_text())
    legacy = [version for version in manifest['legacy']['gameVersions'] if tuple(map(int, version.split('.'))) >= (1, 20, 5)]
    companions = json.loads(args.companion_lock.read_text()) if args.companion_lock else {}
    for version in args.versions or legacy + manifest['modern']['gameVersions']:
        run(args, version, smoke, companions)


if __name__ == '__main__':
    main()
