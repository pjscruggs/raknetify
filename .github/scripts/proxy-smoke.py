"""Check FastMOTD status content, ping framing and maintenance rejection on an isolated Velocity proxy."""

import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import time
import urllib.request


def load_smoke():
    """Reuse the server witness's bounded Minecraft VarInt implementation."""
    spec = importlib.util.spec_from_file_location('server_smoke', Path(__file__).with_name('server-smoke.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def download(item, target):
    """Cache only bytes matching the pinned external plugin or proxy digest."""
    if not target.exists():
        request = urllib.request.Request(item['url'], headers={
            'User-Agent': 'Raknetify2-Compatibility/1.0 (https://github.com/pjscruggs/raknetify2)'})
        with urllib.request.urlopen(request, timeout=60) as response:
            target.write_bytes(response.read())
    assert hashlib.sha512(target.read_bytes()).hexdigest() == item['sha512'], target


def configure(folder, direct, maintenance, port):
    """Use one fixed MOTD and no allowed maintenance clients so every response is predictable."""
    (folder / 'forwarding.secret').write_text('isolated-loopback-fixture\n', encoding='utf-8')
    (folder / 'velocity.toml').write_text(
        'config-version = "2.9"\nbind = "127.0.0.1:' + str(port) + '"\n'
        'motd = "Proxy fallback"\nshow-max-players = 2\nonline-mode = true\n'
        'force-key-authentication = true\nplayer-info-forwarding-mode = "NONE"\n'
        '[servers]\nbackend = "127.0.0.1:1"\ntry = ["backend"]\n'
        '[forced-hosts]\n"lobby.example.com" = ["backend"]\n'
        '"factions.example.com" = ["backend"]\n"minigames.example.com" = ["backend"]\n'
        '[advanced]\nlogin-ratelimit = 0\n[query]\nenabled = false\n', encoding='utf-8')
    config = folder / 'plugins/fastmotd'
    config.mkdir(parents=True, exist_ok=True)
    (config / 'config.yml').write_text(
        'version: "1.0.10"\nserializer: MINIMESSAGE\nmain:\n  enable-updates: false\n'
        '  direct-write: ' + str(direct).lower() + '\n'
        '  version-name: "Transport fixture"\n  descriptions:\n    - "Transport status fixture"\n'
        '  information:\n    - "Ordered sample"\n  favicons:\n    - "absent-fixture-icon.png"\n  fake-online-add-single: 0\n'
        '  fake-online-add-percent: 0\n  max-count: 42\n  domains: {}\n'
        'maintenance:\n  maintenance-enabled: ' + str(maintenance).lower() + '\n'
        '  should-kick-on-join: true\n  kick-whitelist:\n    - "192.0.2.1"\n'
        '  kick-message: "Transport maintenance fixture"\n'
        '  version-name: "Maintenance fixture"\n  descriptions:\n    - "Transport maintenance fixture"\n'
        '  information:\n    - "Maintenance sample"\n  favicons:\n    - "absent-fixture-icon.png"\n  domains: {}\n',
        encoding='utf-8')
    statistics = folder / 'plugins/bStats'
    statistics.mkdir(exist_ok=True)
    (statistics / 'config.txt').write_text('enabled=false\n', encoding='utf-8')


def ready(process, log):
    """Wait for actual proxy startup while rejecting early exit and retaining the diagnostic log."""
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        assert process.poll() is None, log.read_text(encoding='utf-8', errors='replace')
        if 'Done (' in log.read_text(encoding='utf-8', errors='replace'):
            return
        time.sleep(0.25)
    raise TimeoutError('Velocity startup deadline')


def stop(process):
    """Release the fixture port and JVM on successful checks and on every failure path."""
    if process.poll() is not None:
        return
    process.stdin.write('shutdown\n')
    process.stdin.flush()
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)
        raise
    assert process.returncode == 0, process.returncode


def receive(stream, smoke):
    """Read one bounded TCP Minecraft frame without assuming a socket read fills the buffer."""
    length = smoke.read_varint(stream)
    assert 0 < length < 1048576, length
    data = stream.read(length)
    assert len(data) == length, 'Incomplete TCP frame'
    return data


def tcp(port, protocol, maintenance, smoke):
    """Use unmodified TCP as the MOTD reference and also verify its normal ping response."""
    host = b'127.0.0.1'
    handshake = b'\0' + smoke.varint(protocol) + smoke.varint(len(host)) + host + struct.pack('>H', port) + b'\1'
    with socket.create_connection(('127.0.0.1', port), timeout=10) as connection:
        connection.sendall(smoke.varint(len(handshake)) + handshake + b'\1\0')
        stream = connection.makefile('rb')
        packet = io.BytesIO(receive(stream, smoke))
        assert smoke.read_varint(packet) == 0
        length = smoke.read_varint(packet)
        status = json.loads(packet.read(length))
        assert packet.read() == b''
        if not maintenance:
            nonce = 0x1020304050607080
            connection.sendall(b'\x09\x01' + struct.pack('>Q', nonce))
            assert receive(stream, smoke) == b'\x01' + struct.pack('>Q', nonce)
        return status


def udp(args, protocol, mode):
    """Require a successful connected RakNet witness and keep its full output for diagnosis."""
    classpath = os.pathsep.join(str(args.probe_dir / part) for part in ('classes', 'lib/*'))
    process = subprocess.run(
        [args.java, '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', classpath,
         'com.ishland.raknetify.common.connection.RaknetProxyProbe', str(args.port), str(protocol), mode],
        text=True, encoding='utf-8', stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=40)
    output = process.stdout
    (args.work_dir / ('probe-' + str(protocol) + '-' + mode + '.log')).write_text(output, encoding='utf-8')
    assert process.returncode == 0, output
    if mode == 'reject':
        assert 'REJECTED' in output, output
        return None
    assert 'PONG_OK' in output, output
    return json.loads(next(line for line in output.splitlines() if line.startswith('{')))


def displayed(status):
    """Compare visible MOTD data without treating generated player UUIDs as displayed content."""
    players = status['players']
    description = status['description']
    if isinstance(description, str):
        description = {'text': description}
    return {'description': description, 'version': status['version'],
            'online': players['online'], 'max': players['max'],
            'sample': [item['name'] for item in players.get('sample', [])]}


def check_case(args, direct, maintenance, smoke):
    """Verify one FastMOTD write mode while preserving maintenance admission enforcement."""
    configure(args.work_dir, direct, maintenance, args.port)
    log = args.work_dir / ('proxy-' + str(direct) + '-' + str(maintenance) + '.log')
    with log.open('w', encoding='utf-8') as output:
        process = subprocess.Popen([args.java, '-Xmx512M', '-XX:ActiveProcessorCount=2', '-jar', 'velocity.jar'],
                                   cwd=args.work_dir, stdin=subprocess.PIPE, stdout=output,
                                   stderr=subprocess.STDOUT, text=True, encoding='utf-8')
        try:
            ready(process, log)
            for protocol in (763, 769):
                expected = tcp(args.port, protocol, maintenance, smoke)
                actual = udp(args, protocol, 'status')
                assert displayed(actual) == displayed(expected), (expected, actual)
                text = 'Transport maintenance fixture' if maintenance else 'Transport status fixture'
                assert text in json.dumps(actual['description']), actual
                if maintenance:
                    udp(args, protocol, 'reject')
                print(json.dumps({'directWrite': direct, 'maintenance': maintenance, 'protocol': protocol,
                                  'statusParity': True, 'pong': True, 'rejection': maintenance}), flush=True)
        finally:
            stop(process)


def arguments():
    """Accept portable tool and artifact paths without relying on an installed Minecraft profile."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--probe-dir', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--lock', type=Path, default=Path(__file__).parents[1] / 'proxy-versions.json')
    parser.add_argument('--java', default='java')
    parser.add_argument('--port', type=int, default=25891)
    args = parser.parse_args()
    args.probe_dir = args.probe_dir.resolve()
    args.work_dir = args.work_dir.resolve()
    return args


def main():
    """Run the fixed two-protocol matrix for both FastMOTD write modes and maintenance states."""
    args = arguments()
    args.work_dir.mkdir(parents=True, exist_ok=True)
    plugins = args.work_dir / 'plugins'
    plugins.mkdir(exist_ok=True)
    lock = json.loads(args.lock.read_text(encoding='utf-8'))
    download(lock['velocity'], args.work_dir / 'velocity.jar')
    download(lock['fastmotd'], plugins / 'FastMOTD.jar')
    shutil.copyfile(args.jar, plugins / 'raknetify2.jar')
    smoke = load_smoke()
    for direct in (False, True):
        for maintenance in (False, True):
            check_case(args, direct, maintenance, smoke)


if __name__ == '__main__':
    main()
