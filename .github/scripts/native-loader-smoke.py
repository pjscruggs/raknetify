"""Verify packaged native loader mods in isolated, officially installed servers and clients."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import queue
import shutil
import subprocess
import sys
import threading
from types import SimpleNamespace
import urllib.request
import urllib.parse
import uuid
import zipfile


def sibling(name):
    """Reuse the established transport probes without importing executable script entry points."""
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def download(path, url, digest, algorithm='sha1'):
    """Cache an official artifact and reject incomplete or changed bytes before executing them."""
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        temporary = path.with_name(path.name + '.part')
        subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error', '--retry', '3', '--retry-all-errors',
                        '--connect-timeout', '10', '--max-time', '60',
                        '--output', str(temporary), url], check=True, timeout=300)
        assert hashlib.new(algorithm, temporary.read_bytes()).hexdigest() == digest, url
        temporary.replace(path)
    assert hashlib.new(algorithm, path.read_bytes()).hexdigest() == digest, path
    return path


def minecraft_metadata(version):
    """Resolve the selected release through Mojang's launcher manifest rather than a guessed URL."""
    with urllib.request.urlopen('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json', timeout=60) as response:
        manifest = json.load(response)
    entry = next(item for item in manifest['versions'] if item['id'] == version)
    with urllib.request.urlopen(entry['url'], timeout=60) as response:
        content = response.read()
    assert hashlib.sha1(content).hexdigest() == entry['sha1'], entry['url']
    return json.loads(content)


def install(args, lock, side):
    """Install an exact loader into a fixture-owned directory with no saved accounts or game worlds."""
    folder = args.work_dir / side
    folder.mkdir(parents=True, exist_ok=True)
    marker = folder / 'transport-fixture.json'
    expected = {'loader': args.loader, 'version': lock['version'], 'side': side}
    if marker.exists():
        assert json.loads(marker.read_text()) == expected, marker
    else:
        assert not any(folder.iterdir()), 'Refusing to reuse an unowned game directory'
        marker.write_text(json.dumps(expected), encoding='utf-8')
    installer = download(args.work_dir / 'installer.jar', lock['installer'], lock['sha256'], 'sha256')
    if side == 'client':
        (folder / 'launcher_profiles.json').write_text('{"profiles":{}}', encoding='utf-8')
    with (args.work_dir / (side + '-install.log')).open('w', encoding='utf-8') as output:
        subprocess.run([args.java, '-jar', str(installer), '--install' + side.capitalize(), str(folder)],
                       cwd=folder, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=600)
    mods = folder / 'mods'
    mods.mkdir(exist_ok=True)
    assert all(path.name in ('raknetify2.jar', 'transport-test.jar') for path in mods.iterdir()), mods
    shutil.copy2(args.jar, mods / 'raknetify2.jar')
    return folder


def collect_output(process, log, events):
    """Drain owned server output to disk and make startup lines available to the readiness probe."""
    with log.open('w', encoding='utf-8') as output:
        for line in process.stdout:
            output.write(line)
            output.flush()
            events.put(line)


def server_metadata(folder, metadata):
    """Read the protocol number from Mojang's verified bundled server rather than guessing it from the release name."""
    artifact = metadata['downloads']['server']
    path = download(folder / 'vanilla-server.jar', artifact['url'], artifact['sha1'])
    with zipfile.ZipFile(path) as archive:
        return json.loads(archive.read('version.json'))


def configure_server(folder):
    """Limit the disposable offline server to loopback and the single fixture identity on a minimal flat world."""
    player = str(uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:TransportProbe').digest(), version=3))
    (folder / 'whitelist.json').write_text(json.dumps([{'uuid': player, 'name': 'TransportProbe'}]), encoding='utf-8')
    (folder / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    (folder / 'server.properties').write_text(
        'server-ip=127.0.0.1\nserver-port=28990\nonline-mode=false\nview-distance=2\n'
        'simulation-distance=2\nspawn-chunk-radius=0\nlevel-name=transport-test-world\n'
        'level-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock",'
        '"height":1}],"biome":"minecraft:plains"}\n', encoding='utf-8')


def server(args, lock, metadata):
    """Require TCP and connected RakNet responses from the production server JAR, followed by clean shutdown."""
    folder = install(args, lock, 'server')
    expected = server_metadata(folder, metadata)
    configure_server(folder)
    sibling('udp-proxy-fixture').configure(folder, args.proxy_protocol)
    group = 'minecraftforge' if args.loader == 'forge' else 'neoforged'
    filename = 'win_args.txt' if sys.platform == 'win32' else 'unix_args.txt'
    arguments = folder / 'libraries/net' / group / args.loader / lock['version'] / filename
    command = [args.java, '-Xms256M', '-Xmx1G', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
               '@' + str(arguments), 'nogui']
    process = subprocess.Popen(command, cwd=folder, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding='utf-8')
    events = queue.Queue()
    log = args.work_dir / 'server.log'
    reader = threading.Thread(target=collect_output, args=(process, log, events))
    reader.start()
    smoke = sibling('server-smoke')
    try:
        smoke.await_ready(process, events)
        smoke.check_tcp_when_ready(28990, expected)
        with sibling('udp-proxy-fixture').transport(args.proxy_protocol, 28990) as port:
            smoke.check_udp(port)
            smoke.check_connected_udp(port, expected, args.java, args.probe_dir)
            if args.side == 'both':
                args.client_port = port
                client(args, lock, metadata, join=True)
                shutil.copy2(args.work_dir / 'client.log', args.work_dir / 'native-client.log')
            if args.proxy_protocol:
                sibling('udp-proxy-fixture').check_ip_ban(process, folder, args.java, args.probe_dir,
                                                        port, expected['protocol_version'])
    finally:
        smoke.stop_server(process, reader)
    assert process.returncode == 0, process.returncode
    check_failures(log)
    if args.proxy_protocol and args.side == 'both':
        assert 'TransportProbe[/192.0.2.50:' in log.read_text(encoding='utf-8'), 'Forwarded client IP was lost'
    print('PASS', args.loader, lock['minecraft'], 'packaged server TCP, RakNet and clean shutdown', flush=True)


def fabric_server(args, lock, metadata):
    """Require the native client to join a Fabric server so loader negotiation cannot silently break interoperability."""
    smoke = sibling('server-smoke')
    settings = SimpleNamespace(work_dir=args.work_dir / 'fabric-server', jar=args.fabric_jar,
                               encryption_legacy=None, encryption_modern=None)
    folder = smoke.prepare_server(settings, lock['minecraft'], {}, {}, {})
    configure_server(folder)
    sibling('udp-proxy-fixture').configure(folder, args.proxy_protocol)
    log = args.work_dir / 'fabric-server.log'
    events = queue.Queue()
    process, reader = smoke.start_server(folder, log, events, args.java)
    try:
        smoke.await_ready(process, events)
        with sibling('udp-proxy-fixture').transport(args.proxy_protocol, 28990) as port:
            args.client_port = port
            client(args, lock, metadata, join=True)
            shutil.copy2(args.work_dir / 'client.log', args.work_dir / 'fabric-client.log')
    finally:
        smoke.stop_server(process, reader)
    assert process.returncode == 0, process.returncode
    check_failures(log)
    if args.proxy_protocol:
        assert 'TransportProbe[/192.0.2.50:' in log.read_text(encoding='utf-8'), 'Fabric lost forwarded client IP'
    print('PASS', args.loader, 'client joined Fabric server over RakNet', flush=True)


def rule_matches(rule):
    """Apply launcher OS and architecture restrictions without opting into account or demo features."""
    current = 'windows' if sys.platform == 'win32' else 'linux'
    target = rule.get('os', {})
    if rule.get('features') or target.get('name', current) != current:
        return False
    architecture = target.get('arch')
    return architecture is None or architecture.lower() == platform.machine().lower()


def allowed(item):
    """Evaluate ordered launcher rules, retaining unconditional libraries and arguments."""
    result = not item.get('rules')
    for rule in item.get('rules', []):
        if rule_matches(rule):
            result = rule['action'] == 'allow'
    return result


def library_key(library):
    """Match Maven coordinates without the version so loader dependency overrides win."""
    parts = library['name'].split(':')
    return tuple(parts[:2] + parts[3:])


def client_libraries(folder, metadata, profile):
    """Build the actual launcher classpath with loader overrides and verified generated game patches."""
    overrides = {library_key(item) for item in profile['libraries']}
    libraries = profile['libraries'] + [item for item in metadata['libraries'] if library_key(item) not in overrides]
    paths = []
    for library in libraries:
        if allowed(library):
            artifact = library['downloads']['artifact']
            paths.append(download(folder / 'libraries' / artifact['path'], artifact['url'], artifact['sha1']))
    game = metadata['downloads']['client']
    paths.append(download(folder / 'versions' / metadata['id'] / (metadata['id'] + '.jar'), game['url'], game['sha1']))
    return os.pathsep.join(map(str, paths))


def assets(folder, metadata):
    """Install the complete official asset index so client startup has its normal icons, sounds and resources."""
    index = metadata['assetIndex']
    path = download(folder / 'indexes' / (index['id'] + '.json'), index['url'], index['sha1'])
    objects = json.loads(path.read_text(encoding='utf-8'))['objects']
    hashes = sorted({item['hash'] for item in objects.values()})

    transfers = [(folder / 'objects' / digest[:2] / digest,
                  'https://resources.download.minecraft.net/' + digest[:2] + '/' + digest, digest) for digest in hashes]
    download_assets(transfers)


def download_assets(transfers):
    """Reuse a bounded connection pool for the asset batch and admit only complete, hash-verified files."""
    pending = []
    config = []
    for path, url, digest in transfers:
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists():
            assert hashlib.sha1(path.read_bytes()).hexdigest() == digest, path
            continue
        temporary = path.with_name(path.name + '.part')
        config.extend(('url = ' + json.dumps(url, ensure_ascii=False),
                       'output = ' + json.dumps(temporary.as_posix(), ensure_ascii=False)))
        pending.append((path, temporary, digest))
    if not pending:
        return
    subprocess.run(['curl', '--disable', '--parallel', '--parallel-max', '4', '--fail-early', '--fail',
                    '--location', '--silent', '--show-error', '--retry', '3', '--retry-all-errors',
                    '--connect-timeout', '10', '--max-time', '60', '--config', '-'],
                   input='\n'.join(config) + '\n', encoding='utf-8', check=True, timeout=900)
    for path, temporary, digest in pending:
        assert hashlib.sha1(temporary.read_bytes()).hexdigest() == digest, path
        temporary.replace(path)


def launcher_arguments(arguments, values):
    """Expand official JVM argument templates after applying their platform restrictions."""
    result = []
    for item in arguments:
        if isinstance(item, dict):
            if not allowed(item):
                continue
            item = item['value']
        for value in item if isinstance(item, list) else [item]:
            for key, replacement in values.items():
                value = value.replace('${' + key + '}', str(replacement))
            assert '${' not in value, value
            result.append(value)
    return result


def client(args, lock, metadata, join=False):
    """Run shared packet contracts in the packaged client after its ordinary native mod lifecycle finishes."""
    folder = install(args, lock, 'client')
    shutil.copy2(args.fixture, folder / 'mods/transport-test.jar')
    with zipfile.ZipFile(args.work_dir / 'installer.jar') as installer:
        profile = json.loads(installer.read('version.json'))
    asset_directory = args.assets_dir or args.work_dir / 'assets'
    assets(asset_directory, metadata)
    values = {'classpath': client_libraries(folder, metadata, profile), 'library_directory': folder / 'libraries',
              'natives_directory': folder / 'natives', 'launcher_name': 'TransportTests', 'launcher_version': '1'}
    arguments = launcher_arguments(metadata['arguments']['jvm'] + profile['arguments']['jvm'], values)
    command = [args.java, '-Xmx1G', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
               '-Draknetify.test.port=' + str(getattr(args, 'client_port', 28990)),
               '-Draknetify.test.worldChanges=' + str(getattr(args, 'world_changes', False)).lower(),
               '-Draknetify.test.join=' + str(join).lower(), *authentication_arguments(args), *arguments,
               profile['mainClass'], *profile['arguments']['game'], '--gameDir', str(folder),
               '--assetsDir', str(asset_directory), '--assetIndex', metadata['assetIndex']['id'],
               '--version', profile['id'], '--accessToken', '0', '--username', 'TransportProbe', '--offlineDeveloperMode']
    log = args.work_dir / 'client.log'
    with log.open('w', encoding='utf-8') as output:
        subprocess.run(command, cwd=folder, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=180)
    text = log.read_text(encoding='utf-8')
    assert 'PASS native loader client contracts' in text, log
    assert 'PASS native threaded transport closure' in text, log
    if args.loader == 'forge':
        assert 'PASS Forge payload storage ownership' in text, log
    assert 'PASS native gameplay ordering 12' in text, log
    assert 'PASS native entity lifecycle ordering 4' in text, log
    if join:
        assert 'PASS native RakNet login, configuration and playable world' in text, log
    witnesses = sibling('client-address-check')
    witnesses.check_chat_witness(lock['minecraft'], text)
    witnesses.check_transfer_witness(lock['minecraft'], text)
    check_failures(log)
    print('PASS', args.loader, lock['minecraft'], 'packaged client contracts', flush=True)


def authentication_arguments(args):
    """Enable encrypted test logins only through a loopback discovery fixture using the launcher's synthetic identity."""
    url = getattr(args, 'discovery_url', None)
    if url is None:
        return []
    parsed = urllib.parse.urlsplit(url)
    assert parsed.scheme == 'http' and parsed.hostname == '127.0.0.1', 'Session fixture must use loopback'
    assert parsed.port and parsed.path == '/discovery', 'Invalid session fixture endpoint'
    assert not any((parsed.username, parsed.password, parsed.query, parsed.fragment)), 'Unexpected fixture URL fields'
    return ['-Dminecraft.api.discovery.host=' + url, '-Draknetify.test.encrypted=true']


def check_failures(log):
    """Reject transformation and ownership failures even if the game continued to a successful probe."""
    text = log.read_text(encoding='utf-8')
    for failure in ('Critical injection failure', 'Mixin apply failed', 'LEAK:', 'InvalidMixinException',
                    'Error loading class: net/fabricmc', 'Error loading class: org/quiltmc',
                    'DecoderException', 'EncoderException', 'BlockingOperationException'):
        assert failure not in text, (log, failure)


def main():
    """Install and exercise one pinned native loader using the exact artifacts supplied by the build job."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--loader', choices=['forge', 'neoforge'], required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--fabric-jar', type=Path)
    parser.add_argument('--probe-dir', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--assets-dir', type=Path)
    parser.add_argument('--java', default='java')
    parser.add_argument('--side', choices=['client', 'server', 'both'], default='both')
    parser.add_argument('--proxy-protocol', action='store_true')
    args = parser.parse_args()
    args.work_dir = args.work_dir.resolve()
    if args.assets_dir:
        args.assets_dir = args.assets_dir.resolve()
    args.work_dir.mkdir(parents=True, exist_ok=True)
    lock = json.loads((Path(__file__).resolve().parents[2] / 'native-loaders.json').read_text())[args.loader]
    metadata = minecraft_metadata(lock['minecraft'])
    if args.side != 'client':
        server(args, lock, metadata)
    if args.side == 'client':
        client(args, lock, metadata)
    if args.fabric_jar and args.side == 'both':
        fabric_server(args, lock, metadata)


if __name__ == '__main__':
    main()
