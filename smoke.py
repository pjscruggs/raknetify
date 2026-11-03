from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from tempfile import TemporaryDirectory
import hashlib
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import zipfile

HEAD = '76d2cafdcd2c01c6ed813ed66af8fd62aba63230'
VELOCITY_SHA256 = '85be73b90f295acd8d5d45a00ad10bac791d4b72b7554172612a89565ebaaa8f'
VERSIONS = {'1.21.1': ('legacy', 25721), '26.3': ('modern', 25722)}
PROXY_PORT = 25723
ROOT = Path(__file__).resolve().parent
SYSTEM = {'Linux': 'linux', 'Windows': 'windows'}[platform.system()]
JAVA21 = Path(os.environ['JAVA21_HOME']) / 'bin'
JAVA25 = Path(os.environ['JAVA_HOME']) / 'bin'


def fetch(url):
    request = urllib.request.Request(url, headers={
        'User-Agent': 'Raknetify compatibility smoke test (https://github.com/pjscruggs/raknetify)'})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                return response.read()
        except OSError:
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def metadata(url):
    return json.loads(fetch(url))


def download(info, path):
    algorithm = 'sha256' if 'sha256' in info else 'sha1'
    expected = info.get(algorithm)
    if path.is_file() and (not expected or hashlib.new(algorithm, path.read_bytes()).hexdigest() == expected):
        return path
    data = fetch(info['url'])
    if expected and hashlib.new(algorithm, data).hexdigest() != expected:
        raise ValueError('Download checksum mismatch')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path


def allowed(library):
    rules = library.get('rules', [])
    matches = [rule for rule in rules if not rule.get('features')
               and rule.get('os', {}).get('name', SYSTEM) == SYSTEM
               and re.fullmatch(rule.get('os', {}).get('arch', '.*'), 'amd64')]
    return not rules or bool(matches and matches[-1]['action'] == 'allow')


def game_metadata():
    catalog = metadata('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')
    urls = {item['id']: item['url'] for item in catalog['versions']}
    return {version: metadata(urls[version]) for version in VERSIONS}


def prepare_assets(assets, games):
    hashes = set()
    for game in games.values():
        index = game['assetIndex']
        path = download(index, assets / 'indexes' / (index['id'] + '.json'))
        hashes.update(item['hash'] for item in json.loads(path.read_text())['objects'].values())

    def download_object(value):
        relative = value[:2] + '/' + value
        download(dict(url='https://resources.download.minecraft.net/' + relative, sha1=value),
                 assets / 'objects' / relative)

    with ThreadPoolExecutor(max_workers=12) as pool:
        for _ in pool.map(download_object, sorted(hashes)):
            pass


def one_artifact(source, module):
    artifacts = list((source / module / 'build/libs').glob('*-all.jar'))
    if len(artifacts) != 1:
        raise RuntimeError(f'Expected one clean artifact for {module}')
    return artifacts[0]


def binary(home, name):
    return str(home / (name + ('.exe' if SYSTEM == 'windows' else '')))


def prepare_client(session, version, name, game, source):
    folder = session / ('client-' + name)
    mods = folder / 'mods'
    mods.mkdir(parents=True)
    libraries = session / 'libraries'
    paths = []
    for library in game['libraries']:
        item = library.get('downloads', {}).get('artifact')
        if item and allowed(library):
            paths.append(download(item, libraries / item['path']))
    profile = metadata(f'https://meta.fabricmc.net/v2/versions/loader/{version}/0.20.1/profile/json')
    for library in profile['libraries']:
        group, artifact, revision = library['name'].split(':')
        relative = f"{group.replace('.', '/')}/{artifact}/{revision}/{artifact}-{revision}.jar"
        url = library.get('url', 'https://maven.fabricmc.net/') + relative
        paths.append(download(dict(url=url, sha1=library.get('sha1')), libraries / relative))
    paths.append(download(game['downloads']['client'], libraries / (version + '-client.jar')))
    classpath = os.pathsep.join(map(str, paths))
    mod = one_artifact(source, 'fabric' if name == 'legacy' else 'fabric-26')
    shutil.copy2(mod, mods / mod.name)
    probe = 'LegacyClientProbe' if name == 'legacy' else 'ClientProbe'
    classes = folder / 'probe-classes'
    classes.mkdir()
    compiler = JAVA21 if name == 'legacy' else JAVA25
    subprocess.run([binary(compiler, 'javac'), '-proc:none', '-cp', classpath + os.pathsep + str(mod),
                    '-d', str(classes), str(ROOT / (probe + '.java'))], check=True)
    with zipfile.ZipFile(mods / 'probe.jar', 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('fabric.mod.json', json.dumps(dict(schemaVersion=1, id='compatibility_probe',
                         version='1', environment='client', entrypoints=dict(client=['localchecks.' + probe]))))
        for item in classes.rglob('*.class'):
            archive.write(item, item.relative_to(classes).as_posix())
    (folder / 'options.txt').write_text(
        'renderDistance:2\nsimulationDistance:2\nmaxFps:30\npauseOnLostFocus:false\n'
        'soundCategory_master:0.0\nskipMultiplayerWarning:true\nonboardAccessibility:false\ngraphicsMode:0\n')
    return folder, classpath, profile['mainClass'], game['assetIndex']['id']


def prepare_backend(session, version, name, port, game):
    jar = download(game['downloads']['server'], session / 'libraries' / (version + '-server.jar'))
    folder = session / name
    folder.mkdir()
    (folder / 'eula.txt').write_text('eula=true\n')
    (folder / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\n'
        'white-list=false\nenforce-whitelist=false\nlevel-type=minecraft:flat\n'
        'view-distance=2\nsimulation-distance=2\nspawn-protection=0\n')
    return folder, jar


def install_backend_selector(folder, velocity):
    classes = folder / 'selector-classes'
    classes.mkdir()
    subprocess.run([binary(JAVA25, 'javac'), '-proc:none', '-cp', str(velocity),
                    '-d', str(classes), str(ROOT / 'BackendSelector.java')], check=True)
    with zipfile.ZipFile(folder / 'plugins/backend-selector.jar', 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('velocity-plugin.json', json.dumps(dict(
            id='backend-selector', name='Backend Selector', version='1', main='localchecks.BackendSelector')))
        archive.write(classes / 'localchecks/BackendSelector.class', 'localchecks/BackendSelector.class')


def prepare_proxy(session, source):
    folder = session / 'proxy'
    metrics = folder / 'plugins/bStats'
    metrics.mkdir(parents=True)
    (metrics / 'config.txt').write_text('enabled=false\n')
    mod = one_artifact(source, 'velocity')
    shutil.copy2(mod, folder / 'plugins' / mod.name)
    jar = download(dict(url=f'https://fill-data.papermc.io/v1/objects/{VELOCITY_SHA256}/velocity-4.3.0-44.jar',
                        sha256=VELOCITY_SHA256), session / 'libraries/velocity.jar')
    install_backend_selector(folder, jar)
    (folder / 'velocity.toml').write_text(
        'config-version = "2.9"\nbind = "127.0.0.1:25723"\nonline-mode = false\n'
        'force-key-authentication = false\nplayer-info-forwarding-mode = "NONE"\n'
        'forwarding-secret-file = "forwarding.secret"\n'
        '[servers]\nlegacy = "127.0.0.1:25721"\nmodern = "127.0.0.1:25722"\ntry = ["legacy"]\n'
        '[forced-hosts]\n"127.0.0.1" = ["legacy"]\n'
        '[advanced]\nlogin-ratelimit = 0\ncompression-threshold = 256\ncompression-level = -1\n'
        '[packet-limiter]\ninterval = 7\npackets-per-second = -1\nbytes-per-second = -1\n'
        'decompressed-bytes-per-second = 5242880\n')
    return folder, jar


class Processes:
    def __init__(self, session):
        self.session = session
        self.items = []

    def start(self, command, folder, label, stop=None):
        log = self.session / (label + '.log')
        with log.open('w', encoding='utf-8') as stream:
            process = subprocess.Popen(command, cwd=folder, stdin=subprocess.PIPE, stdout=stream,
                                       stderr=subprocess.STDOUT, text=True, encoding='utf-8')
        self.items.append((process, label, stop))
        return process, log

    def stop_all(self):
        exits = []
        for process, label, stop in reversed(self.items):
            if process.poll() is None:
                try:
                    if stop:
                        process.stdin.write(stop + '\n')
                        process.stdin.flush()
                        process.wait(timeout=25)
                    else:
                        process.terminate()
                        process.wait(timeout=10)
                except (OSError, subprocess.TimeoutExpired):
                    process.kill()
                    process.wait()
            exits.append((label, process.returncode))
        return exits


def read_log(path):
    return path.read_text(encoding='utf-8', errors='replace')


def wait_ready(process, log, label):
    deadline = time.monotonic() + 95
    while time.monotonic() < deadline and process.poll() is None:
        if 'Done (' in read_log(log):
            return
        time.sleep(0.5)
    raise RuntimeError(f'{label} did not become ready')


def java(home, heap):
    return [binary(home, 'java'), f'-Xmx{heap}', '-XX:ActiveProcessorCount=4',
            '-Djava.net.preferIPv4Stack=true', '-Draknetify.debug=true']


def report_connection_failure(version, client, result, logs):
    summary = {key: result.get(key) for key in ['udp', 'multichannel', 'chunks', 'passed']}
    print(f'{version} client exit {client.returncode}. Probe observations {json.dumps(summary)}', flush=True)
    markers = ('Connecting to ', 'disconnected with reason:', 'logged in via RakNet',
               'No multi-channel mapping', 'has connected', 'lost connection:',
               'disconnected while connecting', 'Caused by:', 'Exception:',
               'Graphics API', 'Backend API', 'SDL', 'OpenGL', 'GLFW', 'ERROR', 'Stopping')
    for log in logs:
        count = 0
        for line in reversed(read_log(log).splitlines()):
            if any(marker.lower() in line.lower() for marker in markers):
                clean = re.sub(r'(?:[A-Za-z]:[\\/]|/home/|/tmp/|/opt/|/usr/)[^\s]+', '[path]', line)
                print(f'{log.name}: {clean[:250]}', flush=True)
                count += 1
                if count == 12:
                    break


def run_client(processes, version, name, prepared, proxy, proxy_log, assets):
    folder, classpath, main, index = prepared
    user = 'ProbeLegacy' if name == 'legacy' else 'ProbeModern'
    command = java(JAVA21 if name == 'legacy' else JAVA25, '2G') + [
        '-XX:StackShadowPages=32', '--enable-native-access=ALL-UNNAMED',
        '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED', '-cp', classpath, main,
        '--username', user, '--version', version, '--gameDir', str(folder),
        '--assetsDir', str(assets), '--assetIndex', index,
        '--uuid', '00000000000000000000000000000001', '--accessToken', '0',
        '--userType', 'msa', '--versionType', 'release', '--width', '854', '--height', '480',
        '--quickPlayMultiplayer', f'raknet;127.0.0.1:{PROXY_PORT}']
    client, log = processes.start(command, folder, 'client-' + name)
    result_path = folder / 'probe-result.json'
    try:
        client.wait(timeout=140)
    except subprocess.TimeoutExpired:
        result = json.loads(result_path.read_text()) if result_path.is_file() else {}
        report_connection_failure(version, client, result, [log, proxy_log, processes.session / (name + '.log')])
        raise RuntimeError(f'{version} client timed out') from None
    result = json.loads(result_path.read_text()) if result_path.is_file() else {}
    if client.returncode != 0 or not result.get('passed'):
        report_connection_failure(version, client, result, [log, proxy_log, processes.session / (name + '.log')])
        raise RuntimeError(f'{version} connection check failed')
    assert client.returncode == 0 and result['passed'] and result['udp'] and result['multichannel'], version
    assert result['chunks'] > 0 and proxy.poll() is None
    assert f'{user} joined the game' in read_log(processes.session / (name + '.log'))
    assert f'Raknetify: {user} logged in via RakNet' in read_log(proxy_log)
    print(f'PASS {version}: RakNet, multichannel, {result["chunks"]} chunks, matching backend', flush=True)
    return result['chunks']


def exercise(session, source, assets):
    games = game_metadata()
    prepare_assets(assets, games)
    clients = {}
    backends = {}
    for version, (name, port) in VERSIONS.items():
        clients[version] = prepare_client(session, version, name, games[version], source)
        backends[version] = prepare_backend(session, version, name, port, games[version])
    proxy_folder, velocity = prepare_proxy(session, source)
    processes = Processes(session)
    results = {}
    try:
        for version, (name, _) in VERSIONS.items():
            folder, jar = backends[version]
            process, log = processes.start(java(JAVA21 if name == 'legacy' else JAVA25, '1G') +
                                           ['-jar', str(jar), 'nogui'], folder, name, 'stop')
            wait_ready(process, log, name)
        proxy, log = processes.start(java(JAVA25, '512M') + ['-jar', str(velocity)], proxy_folder, 'proxy', 'shutdown')
        wait_ready(proxy, log, 'Velocity')
        for version, (name, _) in VERSIONS.items():
            results[version] = run_client(processes, version, name, clients[version], proxy, log, assets)
    finally:
        exits = processes.stop_all()
    assert all(code == 0 for _, code in exits), exits
    return results


def summarize(results):
    text = f'PR commit `{HEAD}`\n\n'
    text += 'Velocity 4.3.0 build 44, matching vanilla backends and offline test identities.\n\n'
    text += '| Client | RakNet login | Multichannel | Loaded chunks |\n|---|---|---|---:|\n'
    for version, chunks in results.items():
        text += f'| {version} | Pass | Pass | {chunks} |\n'
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as summary:
            summary.write(text)
    print(text)


if __name__ == '__main__':
    source = Path(sys.argv[1]).resolve()
    actual = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    assert actual == HEAD, 'Unexpected Raknetify source commit'
    subprocess.run(['git', '-C', str(source), 'diff-index', '--quiet', 'HEAD', '--'], check=True)
    with TemporaryDirectory(prefix='raknetify-smoke-') as directory:
        session = Path(directory)
        summarize(exercise(session, source, session / 'assets'))
