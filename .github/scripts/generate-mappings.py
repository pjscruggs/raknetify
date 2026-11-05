"""Regenerate proxy routing tables by bootstrapping the supported Minecraft servers.

The manifest supplies the default version matrix. Explicit arguments select a subset.
MAPPINGS_GEN_FRESH requests reconstruction without seeding the existing mapping file.
Each server must exit successfully and write a fresh mapping before the result can
replace the checked-in resource. Cached Fabric launchers stay in the isolated work folder.
"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request


LOADER_VERSION = '0.20.1'
"""Fabric Loader version used for every mapping-generation bootstrap."""

INSTALLER_VERSION = '1.1.1'
"""Fabric installer version embedded in downloaded server launchers."""


def supported_versions(root):
    """Return all advertised and additional mapping versions once, in manifest order."""
    manifest = json.loads((root / 'supported-versions.json').read_text(encoding='utf-8'))
    versions = []
    for group in manifest.values():
        versions.extend(group['gameVersions'])
        versions.extend(group.get('additionalMappingVersions', []))
    return list(dict.fromkeys(versions))


def prepare_work(root):
    """Create the isolated mapping folder and seed its output unless a fresh run was requested.

    Only JAR files in the dedicated mods directory are removed. The cached server
    launchers and other work files remain available for subsequent mapping runs.
    """
    work = root / 'run-mappingsGen'
    mods = work / 'mods'
    mods.mkdir(parents=True, exist_ok=True)
    for jar in mods.glob('*.jar'):
        jar.unlink()
    output = work / 'channelMappings.json'
    output.unlink(missing_ok=True)
    if not os.environ.get('MAPPINGS_GEN_FRESH'):
        shutil.copy2(root / 'common/src/main/resources/raknetify-channel-mappings.json', output)
    return work


def server_launcher(work, version):
    """Reuse or download the pinned Fabric launcher for one Minecraft version."""
    name = f'fabric-server-mc{version}-loader{LOADER_VERSION}-installer{INSTALLER_VERSION}.jar'
    launcher = work / name
    if launcher.exists():
        print(f'Using existing {name}', flush=True)
        return launcher
    url = (f'https://meta.fabricmc.net/v2/versions/loader/{version}/'
           f'{LOADER_VERSION}/{INSTALLER_VERSION}/server/jar')
    with urllib.request.urlopen(url, timeout=60) as response:
        content = response.read()
    launcher.write_bytes(content)
    return launcher


def generate_version(work, version):
    """Run one server bootstrap and require a newly written mapping file before continuing."""
    launcher = server_launcher(work, version)
    time.sleep(1)
    marker = work / '.mapping-generation-start'
    marker.touch()
    subprocess.run(['java', '-Draknetify.saveChannelMappings=true',
                    '-Draknetify.saveChannelMappings.exit=true', '-jar', launcher.name],
                   cwd=work, check=True)
    output = work / 'channelMappings.json'
    if not output.exists() or output.stat().st_mtime_ns <= marker.stat().st_mtime_ns:
        raise RuntimeError(f'Mapping generation did not write output for {version}')


def main():
    """Build the universal mod, generate every selected version, and publish the complete local result."""
    root = Path(__file__).resolve().parents[2]
    versions = sys.argv[1:] or supported_versions(root)
    print(' '.join(versions), flush=True)
    wrapper = root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    subprocess.run([str(wrapper), 'clean', 'build'], cwd=root, check=True)
    work = prepare_work(root)
    jars = list((root / 'fabric-universal/build/libs').glob('raknetify2-fabric-*.jar'))
    if len(jars) != 1:
        raise RuntimeError(f'Expected one universal Fabric JAR, found {len(jars)}')
    shutil.copy2(jars[0], work / 'mods' / jars[0].name)
    for version in versions:
        generate_version(work, version)
    shutil.copy2(work / 'channelMappings.json',
                 root / 'common/src/main/resources/raknetify-channel-mappings.json')


if __name__ == '__main__':
    main()
