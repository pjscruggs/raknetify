"""Validate release provenance and artifact integrity before publication credentials become available."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tomllib
import zipfile


def validate_run(run, repository):
    """Accept only a completed successful build of this repository's main branch, never a pull-request artifact."""
    if repository != 'pjscruggs/raknetify2':
        raise ValueError('Unexpected publication repository')
    if run['head_repository']['full_name'] != repository:
        raise ValueError('Build belongs to another repository')
    if run['path'] != '.github/workflows/build.yml':
        raise ValueError('Not the release-gating build workflow')
    if run['event'] not in ('push', 'workflow_dispatch'):
        raise ValueError('Untrusted build event')
    if run['head_branch'] != 'main':
        raise ValueError('Only main builds may be published')
    if run['status'] != 'completed' or run['conclusion'] != 'success':
        raise ValueError('Build has not passed')
    if not re.fullmatch('[0-9a-f]{40}', run['head_sha']):
        raise ValueError('Malformed source revision')
    return run['head_sha']


def publication_matrix(loader, destination):
    """Select independent upload jobs so a failed destination can be retried without replaying successful jobs."""
    loaders = ['fabric', 'forge', 'neoforge'] if loader in ('all', 'all-mods') else [loader]
    destinations = ['modrinth', 'curseforge'] if destination == 'both' else [destination]
    validate_targets(loaders, destinations)
    return {'include': [{'loader': item, 'destination': target} for item in loaders for target in destinations]}


def validate_targets(loaders, destinations):
    """Reject unknown destinations and prevent a server plugin from being uploaded to a mod-only project."""
    if not all(item in ('fabric', 'forge', 'neoforge', 'spigot') for item in loaders):
        raise ValueError('Unknown loader')
    if not all(item in ('modrinth', 'curseforge') for item in destinations):
        raise ValueError('Unknown destination')
    if 'spigot' in loaders and 'curseforge' in destinations:
        raise ValueError('Spigot requires a separate CurseForge Bukkit project')


def verify_checksums(folder):
    """Require flat, unique artifact paths and matching SHA-256 values for every staged release file."""
    checked = set()
    for line in (folder / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  \./([A-Za-z0-9_.+-]+)', line)
        if not match:
            raise ValueError('Invalid checksum entry')
        digest, name = match.groups()
        if name in checked:
            raise ValueError('Duplicate artifact checksum')
        path = folder / name
        if not path.is_file() or path.is_symlink():
            raise ValueError('Missing or indirect artifact: ' + name)
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('Changed artifact: ' + name)
        checked.add(name)
    actual = {path.name for path in folder.iterdir() if path.name != 'SHA256SUMS'}
    if actual != checked:
        raise ValueError('Artifact inventory differs from its checksums')
    return checked


def verify_mod_version(path, loader, version):
    """Reject stale or mislabeled JARs by reading the metadata consumed by their actual mod loaders."""
    with zipfile.ZipFile(path) as archive:
        if loader == 'fabric':
            metadata = json.loads(archive.read('fabric.mod.json'))
            if metadata['id'] != 'raknetify':
                raise ValueError('Wrong Fabric mod')
            verify_fabric_entrypoints(archive)
        elif loader == 'spigot':
            metadata = spigot_metadata(archive)
        else:
            filename = 'mods.toml' if loader == 'forge' else 'neoforge.mods.toml'
            metadata = next(item for item in tomllib.loads(archive.read('META-INF/' + filename).decode())['mods']
                            if item['modId'] == 'raknetify')
        if metadata['version'] != version:
            raise ValueError('JAR metadata version does not match the release')
        if any(name.startswith('com/ishland/raknetify/test/') for name in archive.namelist()):
            raise ValueError('Test fixture in release')


def verify_fabric_entrypoints(archive):
    """Reject minimization that removes metadata-discovered entrypoints from either nested Fabric runtime."""
    metadata = json.loads(archive.read('fabric.mod.json'))
    for entries in metadata.get('entrypoints', {}).values():
        for entry in entries:
            name = entry if isinstance(entry, str) else entry['value']
            path = name.split('::')[0].replace('.', '/') + '.class'
            if path not in archive.namelist():
                raise ValueError('Missing Fabric entrypoint: ' + name)
    for nested in metadata.get('jars', []):
        with zipfile.ZipFile(io.BytesIO(archive.read(nested['file']))) as dependency:
            verify_fabric_entrypoints(dependency)


def spigot_metadata(archive):
    """Read the generated Bukkit metadata strictly and reject ambiguous versions or another plugin entry point."""
    lines = archive.read('plugin.yml').decode('utf-8').splitlines()
    if 'name: Raknetify2' not in lines:
        raise ValueError('Wrong Spigot plugin')
    if 'main: com.ishland.raknetify.spigot.RaknetifySpigotPlugin' not in lines:
        raise ValueError('Wrong Spigot entry point')
    versions = [line for line in lines if line.startswith('version:')]
    if len(versions) != 1:
        raise ValueError('Ambiguous Spigot version')
    match = re.fullmatch(r"version: '([^']+)'", versions[0])
    if not match:
        raise ValueError('Malformed Spigot version')
    return {'version': match.group(1)}


def verify_artifacts(folder):
    """Verify the complete release bundle and return the single clean version shared by every mod artifact."""
    checked = verify_checksums(folder)
    metadata = json.loads((folder / 'compatibility.json').read_text(encoding='utf-8'))
    version = metadata['version']
    if not re.fullmatch(r'\d+\.\d+\.\d+-alpha\.\d+\.\d+', version):
        raise ValueError('Not a clean release version')
    for loader in ('fabric', 'forge', 'neoforge'):
        name = 'raknetify2-' + loader + '-' + version + '.jar'
        if name not in checked:
            raise ValueError('Missing release loader: ' + loader)
        verify_mod_version(folder / name, loader, version)
    plugins = [name for name in checked if name.startswith('raknetify2-spigot-')]
    if plugins:
        expected = 'raknetify2-spigot-' + version + '.jar'
        if plugins != [expected]:
            raise ValueError('Unexpected Spigot artifact')
        verify_mod_version(folder / expected, 'spigot', version)
    return version


def write_outputs(values):
    """Emit only nonsecret validated metadata to the GitHub output file or the local verification console."""
    if 'GITHUB_OUTPUT' in os.environ:
        with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as output:
            for key, value in values.items():
                output.write(key + '=' + value + '\n')
    else:
        print(json.dumps(values, sort_keys=True))


def main():
    """Validate either the selected workflow run or its downloaded artifact bundle before an upload job begins."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-id')
    parser.add_argument('--repository', default='pjscruggs/raknetify2')
    parser.add_argument('--loader', default='all')
    parser.add_argument('--destination', default='both')
    parser.add_argument('--artifacts', type=Path)
    args = parser.parse_args()
    if args.artifacts:
        write_outputs({'version': verify_artifacts(args.artifacts)})
        return
    if not (args.run_id and args.run_id.isdecimal()):
        raise ValueError('A numeric build run ID is required')
    output = subprocess.check_output(['gh', 'api', 'repos/' + args.repository + '/actions/runs/' + args.run_id], encoding='utf-8')
    revision = validate_run(json.loads(output), args.repository)
    matrix = publication_matrix(args.loader, args.destination)
    write_outputs({'revision': revision, 'matrix': json.dumps(matrix, separators=(',', ':'))})


if __name__ == '__main__':
    main()
