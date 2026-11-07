"""Run real Fabric client address classes without starting graphics or joining a server."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request


def fetch_json(url):
    """Read official launcher metadata for the explicitly selected Minecraft and loader versions."""
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def download(folder, url, digest=None, algorithm='sha1'):
    """Cache a dependency by URL and verify its published digest before use."""
    path = folder / (hashlib.sha256(url.encode()).hexdigest()[:16] + '-' + url.rsplit('/', 1)[-1])
    if not path.exists():
        with urllib.request.urlopen(url, timeout=60) as response:
            path.write_bytes(response.read())
    if digest and hashlib.new(algorithm, path.read_bytes()).hexdigest() != digest:
        raise ValueError("Artifact digest mismatch: " + str(url))
    return path


def allowed(library):
    """Apply launcher OS rules without enabling optional feature-specific libraries."""
    current = 'windows' if sys.platform == 'win32' else 'linux'
    result = not library.get('rules')
    for rule in library.get('rules', []):
        matches = rule.get('os', {}).get('name', current) == current and not rule.get('features')
        if matches:
            result = rule['action'] == 'allow'
    return result


def profile_libraries(cache, profile):
    """Resolve exact Maven artifacts from the Fabric launch profile."""
    result = []
    for library in profile['libraries']:
        group, artifact, version = library['name'].split(':')
        relative = group.replace('.', '/') + '/' + artifact + '/' + version + '/' + artifact + '-' + version + '.jar'
        url = library['url'] + relative
        result.append(download(cache, url, library.get('sha256'), 'sha256'))
    return result


def library_key(library):
    """Identify a Maven artifact independently of its version so Fabric can replace vanilla's ASM libraries."""
    parts = library['name'].split(':')
    return tuple(parts[:2] + parts[3:])


def minecraft_libraries(cache, metadata, profile):
    """Apply loader dependency overrides before assembling the client classpath, as a normal launcher does."""
    overrides = {library_key(library) for library in profile['libraries']}
    result = []
    for library in metadata['libraries']:
        if library_key(library) in overrides or not allowed(library):
            continue
        item = library.get('downloads', {}).get('artifact')
        if item:
            result.append(download(cache, item['url'], item['sha1']))
    return result


def prepare(args, version, metadata, lock):
    """Install the isolated client libraries and pinned mods without assets or real account data."""
    folder = (args.work_dir / version).resolve()
    (folder / 'mods').mkdir(parents=True, exist_ok=True)
    cache = args.work_dir.resolve() / 'downloads'
    cache.mkdir(exist_ok=True)
    profile = fetch_json('https://meta.fabricmc.net/v2/versions/loader/' + version + '/0.20.1/profile/json')
    libraries = profile_libraries(cache, profile)
    libraries.extend(minecraft_libraries(cache, metadata, profile))
    item = metadata['downloads']['client']
    libraries.append(download(cache, item['url'], item['sha1']))
    for item in lock.get(version, []):
        artifact = download(cache, item['url'], item['sha512'], 'sha512')
        shutil.copy2(artifact, folder / 'mods' / item['filename'])
    if not args.baseline:
        shutil.copy2(args.jar, folder / 'mods/raknetify2.jar')
    shutil.copy2(args.fixture, folder / 'mods/address-test.jar')
    (folder / 'hosts').write_text('192.0.2.2 fixture.test\n127.0.0.1 localhost\n::1 localhost\n')
    (folder / 'minecraft.json').write_text(json.dumps(metadata, indent=2))
    (folder / 'fabric.json').write_text(json.dumps(profile, indent=2))
    return folder, libraries


def java_for(args, version):
    """Use the Java generation expected by the selected client without machine-specific defaults."""
    if version.startswith('26.'):
        return args.java_modern
    return args.java_21 if tuple(map(int, version.split('.'))) >= (1, 20, 5) else args.java_17


def launch_command(args, version, folder, libraries):
    """Build a headless fixture launch with a private hosts file and no usable account credentials."""
    return [java_for(args, version), '-Xmx1G', '-Djava.awt.headless=true', '-Dmixin.debug.export=true',
               '-Dmixin.debug.export.decompile=false',
               '-Draknetify.test.addresses=true', '-Draknetify.test.orderingOnly=' + str(args.ordering_only).lower(), '-Draknetify.test.baseline=' + str(args.baseline).lower(), '-Djdk.net.hosts.file=' + str(folder / 'hosts'),
               '-cp', os.pathsep.join(map(str, libraries)), 'net.fabricmc.loader.impl.launch.knot.KnotClient',
               '--gameDir', str(folder), '--version', version, '--accessToken', '0', '--username', 'AddressProbe']


def clear_mixin_exports(folder):
    """Discard prior fixture exports so a skipped transformation cannot pass with stale bytecode."""
    export = folder / '.mixin.out'
    if export.is_symlink() or export.resolve().parent != folder.resolve():
        raise ValueError('Mixin export directory is outside the isolated client folder')
    if export.exists():
        shutil.rmtree(export)


def run(args, version, metadata, lock):
    """Require all native client address witnesses and fail on mixin or linkage errors."""
    folder, libraries = prepare(args, version, metadata, lock)
    clear_mixin_exports(folder)
    command = launch_command(args, version, folder, libraries)
    with (folder / 'client.log').open('w', encoding='utf8') as output:
        result = subprocess.run(command, cwd=folder, stdout=output, stderr=subprocess.STDOUT, timeout=180)
    text = (folder / 'client.log').read_text()
    assert result.returncode == 0, str(folder / 'client.log')
    assert 'PASS native client address compatibility' in text
    if not args.ordering_only:
        assert '\t- fastipping ' in text, 'Fast IP Ping was not loaded'
    if args.baseline:
        assert 'PASS standalone resolver baseline' in text
    else:
        assert '\t- raknetify ' in text, 'Raknetify was not loaded'
        if not version.startswith('26.'):
            assert 'PASS native legacy mixin call sites and rejected missing-hook control' in text
        assert 'PASS native gameplay ordering 12' in text
        assert 'PASS native entity lifecycle ordering 4' in text
        check_transfer_witness(version, text)
        check_chat_witness(version, text)
        if not args.ordering_only:
            assert text.count('PASS connect address ') == 18
            assert text.count('PASS ping address ') == 18
    for failure in ['Mixin apply failed', 'Critical injection failure', 'NoClassDefFoundError']:
        assert failure not in text, failure
    scope = 'standalone resolver baseline' if args.baseline else 'native client IPv4, IPv6 and hostname resolution with TCP and both RakNet prefixes'
    if args.ordering_only:
        scope = 'native gameplay packet ordering under reversed delivery'
    print('PASS', version, scope, flush=True)


def check_chat_witness(version, text):
    """Require native session dependencies and cryptographic chain validation on session-indexed chat versions."""
    if version.startswith('26.') or tuple(map(int, version.split('.'))) >= (1, 19, 3):
        assert 'PASS native serverbound chat ordering 5' in text
        assert 'PASS native clientbound chat ordering 5' in text
        assert 'PASS native signed chat chain with rejected out-of-order control' in text
        print('PASS', version, 'native signed chat chain and session ordering', flush=True)
    if version.startswith('26.') or tuple(map(int, version.split('.'))) >= (1, 20, 5):
        assert 'PASS native signed command ordering 4' in text


def check_transfer_witness(version, text):
    """Require actual query handling and send-overload checks on every version with transfer packets."""
    if version.startswith('26.') or tuple(map(int, version.split('.'))) >= (1, 20, 5):
        assert 'PASS native transfer capability and send overloads' in text
        print('PASS', version, 'native transfer capability and send overloads', flush=True)


def supported_versions():
    """Select every advertised version for standalone native packet-ordering checks."""
    manifest = Path(__file__).resolve().parents[2] / 'supported-versions.json'
    groups = json.loads(manifest.read_text())
    return {version: [] for group in groups.values() for version in group['gameVersions']}


def main():
    """Run every pinned shared version or an explicitly requested subset."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--lock', type=Path)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--java-17', default='java')
    parser.add_argument('--java-21', default='java')
    parser.add_argument('--java-modern', default='java')
    parser.add_argument('--versions', nargs='+')
    parser.add_argument('--baseline', action='store_true')
    parser.add_argument('--ordering-only', action='store_true')
    args = parser.parse_args()
    if args.ordering_only and args.baseline:
        parser.error('Ordering checks require Raknetify')
    if not args.ordering_only and not args.lock:
        parser.error('Address checks require a companion lock')
    lock = json.loads(args.lock.read_text()) if args.lock else supported_versions()
    manifest = fetch_json('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')
    versions = {item['id']: item for item in manifest['versions']}
    for version in args.versions or list(lock):
        metadata = fetch_json(versions[version]['url'])
        run(args, version, metadata, lock)


if __name__ == '__main__':
    main()
