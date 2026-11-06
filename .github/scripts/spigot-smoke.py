"""Verify packaged Spigot and Paper plugins with native encrypted clients and authentication rejection controls."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import threading
import time
from types import SimpleNamespace
import zipfile


def sibling(name):
    """Load a shared fixture module without executing its command-line entry point."""
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def prepare_server(args, lock, native):
    """Download a pinned Paper build or compile official Spigot sources and verify every selected revision."""
    if args.platform == 'paper':
        entry = lock['paper']
        return native.download(args.work_dir / 'paper.jar', entry['url'], entry['sha256'], 'sha256')
    folder = args.work_dir / 'buildtools'
    folder.mkdir(exist_ok=True)
    entry = lock['spigot']
    tool = native.download(folder / 'BuildTools.jar', entry['buildTools'], entry['sha256'], 'sha256')
    with (args.work_dir / 'buildtools.log').open('w', encoding='utf-8') as output:
        subprocess.run([args.java, '-jar', str(tool), '--rev', lock['minecraft'], '--compile', 'SPIGOT', '--nogui'],
                       cwd=folder, env=maven_environment(args, entry['maven'], native),
                       stdout=output, stderr=subprocess.STDOUT, check=True, timeout=1200)
    for name, expected in entry['refs'].items():
        actual = subprocess.check_output(['git', '-C', str(folder / name), 'rev-parse', 'HEAD'], text=True).strip()
        assert actual == expected, 'Spigot source changed: ' + name
    return folder / ('spigot-' + lock['minecraft'] + '.jar')


def maven_environment(args, entry, native):
    """Give BuildTools its pinned Maven distribution with bounded downloads instead of its unbounded archive request."""
    archive = native.download(args.work_dir / 'maven.zip', entry['url'], entry['sha512'], 'sha512')
    with zipfile.ZipFile(archive) as distribution:
        distribution.extractall(args.work_dir / 'maven')
    home = args.work_dir / 'maven' / ('apache-maven-' + entry['version'])
    assert (home / 'bin/mvn').is_file() and (home / 'bin/mvn.cmd').is_file(), 'Incomplete Maven distribution'
    return os.environ | {'M2_HOME': str(home)}


def configure_server(args, native):
    """Create a whitelisted loopback fixture and require native online authentication through the local mock service."""
    folder = args.work_dir / 'server'
    folder.mkdir(exist_ok=True)
    marker = folder / 'transport-fixture.json'
    if marker.exists():
        assert json.loads(marker.read_text()) == {'platform': args.platform}, 'Different server fixture owns this directory'
    else:
        assert not any(folder.iterdir()), 'Refusing to reuse an unowned server directory'
        marker.write_text(json.dumps({'platform': args.platform}), encoding='utf-8')
    native.configure_server(folder)
    sibling('udp-proxy-fixture').configure(folder, args.proxy_protocol)
    if args.platform == 'paper':
        configure_paper_proxy(folder, args.proxy_protocol)
    properties = folder / 'server.properties'
    properties.write_text(properties.read_text().replace('online-mode=false', 'online-mode=true')
                          + 'white-list=true\nenforce-whitelist=true\nenforce-secure-profile=false\n', encoding='utf-8')
    plugins = folder / 'plugins'
    plugins.mkdir(exist_ok=True)
    shutil.copy2(args.jar, plugins / 'raknetify2.jar')
    return folder


def configure_paper_proxy(folder, enabled):
    """Exercise Paper's native required-header setting alongside the separate UDP decoder."""
    path = folder / 'config/paper-global.yml'
    path.parent.mkdir(parents=True, exist_ok=True)
    text = path.read_text(encoding='utf-8') if path.exists() else '_version: 31\nproxies:\n  proxy-protocol: false\n'
    import re
    text, count = re.subn(r'(?m)^  proxy-protocol: (?:true|false)$',
                         '  proxy-protocol: ' + str(enabled).lower(), text)
    assert count == 1, 'Paper proxy configuration schema changed'
    path.write_text(text, encoding='utf-8')


def await_transport(process, events):
    """Wait for this plugin's listener instead of assuming the server's earlier Done line implies UDP readiness."""
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        assert process.poll() is None, 'Server exited before RakNet became ready'
        try:
            line = events.get(timeout=1)
        except queue.Empty:
            continue
        assert 'Could not start RakNet' not in line, line
        if 'RakNet is listening on UDP port 28990' in line:
            return
    raise AssertionError('RakNet listener startup timed out')


def rejected_logins(args, authority):
    """Require encrypted session rejection and plaintext nonce rejection, with only the valid nonce reaching authentication."""
    classpath = os.pathsep.join((str(args.probe_dir / 'classes'), str(args.probe_dir / 'lib/*')))
    modes = ('rejected-session', 'rejected-throttle', 'rejected-nonce') if args.proxy_protocol else ('rejected-session', 'rejected-nonce')
    for mode in modes:
        subprocess.run([args.java, '-cp', classpath,
                        'com.ishland.raknetify.common.connection.RaknetEncryptionProbe', str(args.client_port), '777', '8', mode],
                       check=True, timeout=40)
        if args.proxy_protocol and mode != 'rejected-session':
            # Preserve Bukkit's default four-second IP throttle while testing separate authentication failures.
            time.sleep(4.1)
    assert len(authority.rejected) == 1, 'A failed nonce reached account verification'
    assert not authority.accepted, 'Rejection probes admitted a player'


def join_server(args, authority, native, server):
    """Launch the real native client and require a playable encrypted RakNet session, then verify its auth digest."""
    repository = Path(__file__).resolve().parents[2]
    lock = json.loads((repository / 'native-loaders.json').read_text(encoding='utf-8'))['neoforge']
    settings = SimpleNamespace(loader='neoforge', java=args.java, work_dir=args.work_dir / 'client-runtime',
                               assets_dir=args.assets_dir, fixture=args.client_fixture, jar=args.client_jar,
                               discovery_url=authority.url, world_changes=True, client_port=args.client_port)
    settings.work_dir.mkdir(exist_ok=True)
    log = settings.work_dir / 'client.log'
    log.write_text('', encoding='utf-8')
    cancelled = threading.Event()
    with ThreadPoolExecutor(max_workers=1) as workers:
        transitions = workers.submit(change_worlds, server, log, args.work_dir / 'server.log', cancelled)
        try:
            native.client(settings, lock, native.minecraft_metadata(lock['minecraft']), join=True)
            transitions.result(timeout=5)
        finally:
            cancelled.set()
    shutil.copy2(settings.work_dir / 'client.log', args.work_dir / 'client.log')
    authority.check_complete()


def change_worlds(server, log, server_log, cancelled):
    """Send each dimension change only after the actual client reports the preceding playable world."""
    sequence = [('0 minecraft:overworld', 'minecraft:the_nether'), ('1 minecraft:the_nether', 'minecraft:overworld')]
    for marker, destination in sequence:
        wait_marker(log, 'PASS native world boundary ' + marker, cancelled)
        server.stdin.write('experience set TransportProbe 0 levels\n')
        server.stdin.write('execute as TransportProbe in ' + destination + ' run tp @s 0 64 0\n')
        server.stdin.flush()
    wait_marker(log, 'PASS native final chat sent', cancelled)
    wait_marker(server_log, 'Raknetify transport fixture joined', cancelled)
    server.stdin.write('experience set TransportProbe 42 levels\n')
    server.stdin.flush()


def wait_marker(log, marker, cancelled):
    """Bound marker polling and stop promptly if the client process fails during installation or connection."""
    deadline = time.monotonic() + 600
    while not cancelled.wait(0.1):
        assert time.monotonic() < deadline, 'Client did not reach the required world boundary'
        if marker in log.read_text(encoding='utf-8', errors='replace'):
            return
    raise AssertionError('Client stopped before the world-change sequence completed')


def exercise(args, lock, native, authority, server_jar):
    """Run the production plugin through status, native rejection controls, and optional real-client gameplay."""
    folder = configure_server(args, native)
    command = [args.java, '-Xms256M', '-Xmx1G', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
               '-Dminecraft.api.discovery.host=' + authority.url, '-jar', str(server_jar), 'nogui']
    process = subprocess.Popen(command, cwd=folder, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding='utf-8')
    events = queue.Queue()
    log = args.work_dir / 'server.log'
    reader = threading.Thread(target=native.collect_output, args=(process, log, events))
    reader.start()
    smoke = sibling('server-smoke')
    try:
        await_transport(process, events)
        expected = {'name': args.platform.capitalize() + ' ' + lock['minecraft'], 'protocol_version': lock['protocol']}
        smoke.check_tcp_when_ready(28990, expected, args.proxy_protocol and args.platform == 'paper')
        with sibling('udp-proxy-fixture').transport(args.proxy_protocol, 28990, repeated=True) as port:
            args.client_port = port
            smoke.check_udp(port)
            smoke.check_connected_udp(port, expected, args.java, args.probe_dir)
            rejected_logins(args, authority)
            if args.side == 'both':
                join_server(args, authority, native, process)
    finally:
        smoke.stop_server(process, reader)
    assert process.returncode == 0, process.returncode
    native.check_failures(log)
    text = log.read_text(encoding='utf-8')
    assert 'EncryptionProbe joined the game' not in text, 'Rejected identity reached gameplay'
    if args.side == 'both':
        assert 'Raknetify transport fixture joined' in text, 'Native server did not receive the real client chat'
        if args.proxy_protocol:
            assert 'TransportProbe[/192.0.2.50:' in text, 'Forwarded client IP was lost'
    print('PASS', args.platform, lock['minecraft'], 'packaged plugin, authentication controls and clean shutdown')


def main():
    """Verify one officially sourced server with the exact plugin and client artifacts produced by the build job."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--platform', choices=['spigot', 'paper'], required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--probe-dir', type=Path, required=True)
    parser.add_argument('--client-jar', type=Path, required=True)
    parser.add_argument('--client-fixture', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--assets-dir', type=Path)
    parser.add_argument('--java', default='java')
    parser.add_argument('--side', choices=['server', 'both'], default='both')
    parser.add_argument('--proxy-protocol', action='store_true')
    args = parser.parse_args()
    args.work_dir = args.work_dir.resolve()
    args.probe_dir = args.probe_dir.resolve()
    if args.assets_dir:
        args.assets_dir = args.assets_dir.resolve()
    args.work_dir.mkdir(parents=True, exist_ok=True)
    native = sibling('native-loader-smoke')
    lock = json.loads((Path(__file__).resolve().parents[2] / 'spigot-servers.json').read_text(encoding='utf-8'))
    server_jar = prepare_server(args, lock, native)
    with sibling('session-fixture').SessionAuthority() as authority:
        exercise(args, lock, native, authority, server_jar)


if __name__ == '__main__':
    main()
