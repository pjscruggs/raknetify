"""Run the encrypted loopback witness with the selected Minecraft client's native login packets."""
import importlib.util
import os
from pathlib import Path
import subprocess
from types import SimpleNamespace


def sibling(name):
    """Load shared launcher functions without executing another fixture's command-line interface."""
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run(probe_dir, work_dir, request):
    """Use official client libraries and compiled transport code without graphics, assets, or account credentials."""
    client = sibling('client-address-check')
    native = sibling('native-loader-smoke')
    probe_dir = probe_dir.resolve()
    args = SimpleNamespace(work_dir=work_dir, baseline=True, ordering_only=True,
                           fixture=probe_dir / 'native-login-test.jar',
                           java_17=request.java, java_21=request.java, java_modern=request.java)
    metadata = native.minecraft_metadata(request.version)
    folder, libraries = client.prepare(args, request.version, metadata, {})
    libraries.extend(sorted((probe_dir / 'lib').glob('netty-raknet-*.jar')))
    libraries.extend(sorted((probe_dir / 'lib').glob('commons-math3-*.jar')))
    command = client.launch_command(args, request.version, folder, libraries)
    connection = ','.join(map(str, (request.port, request.protocol, request.payload_size)))
    command[1:1] = ['-Draknetify.test.encryption=' + connection,
                    '-Draknetify.test.encryptionModes=' + ','.join(request.modes)]
    log = folder / 'encryption.log'
    with log.open('w', encoding='utf-8') as output:
        result = subprocess.run(command, cwd=folder, stdout=output, stderr=subprocess.STDOUT, timeout=120)
    text = log.read_text(encoding='utf-8', errors='replace')
    if result.returncode != 0 or 'PASS native client login packet serialization' not in text:
        raise RuntimeError('Native encryption probe failed: ' + os.fspath(log) + '\n' + text[-6000:])
    for mode in request.modes:
        marker = 'PASS native RSA and bidirectional RakNet encryption' if mode == 'echo' else 'PASS native authentication ' + mode
        if marker not in text:
            raise RuntimeError('Missing encryption witness ' + marker + ': ' + os.fspath(log))
    print('PASS', request.version, 'native login packet serialization and', ', '.join(request.modes), flush=True)
