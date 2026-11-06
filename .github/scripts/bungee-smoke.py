"""Verify BungeeCord's packaged plugin with direct and required-PROXY UDP status connections."""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import time


def sibling(name):
    """Reuse existing bounded proxy probes without running another script's command-line entry point."""
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def configure(args, forwarded):
    """Write a disposable loopback proxy with no reachable backend and an explicit native PROXY requirement."""
    (args.work_dir / 'config.yml').write_text(
        'online_mode: true\nip_forward: false\nconnection_throttle: 0\n'
        'servers:\n  lobby:\n    address: 127.0.0.1:1\n    restricted: false\n'
        'listeners:\n- host: 127.0.0.1:' + str(args.port) + '\n  motd: Transport Bungee fixture\n'
        '  max_players: 42\n  query_enabled: false\n  ping_passthrough: false\n'
        '  priorities: [lobby]\n  forced_hosts: {}\n  proxy_protocol: ' + str(forwarded).lower() + '\n',
        encoding='utf-8')
    sibling('udp-proxy-fixture').configure(args.work_dir, forwarded)


def ready(process, log):
    """Wait for both Bungee's TCP listener and Raknetify2's UDP bind, preserving early startup failures."""
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        text = log.read_text(encoding='utf-8', errors='replace')
        assert process.poll() is None, text
        if 'Listening on' in text and 'Raknetify server started on' in text:
            return
        time.sleep(0.1)
    raise AssertionError('Bungee listeners did not become ready: ' + log.read_text(encoding='utf-8'))


def run_case(args, forwarded):
    """Compare native TCP and connected RakNet status while proving required UDP metadata cannot be bypassed."""
    configure(args, forwarded)
    log = args.work_dir / ('bungee-proxy-' + str(forwarded) + '.log')
    probes = sibling('proxy-smoke')
    smoke = sibling('server-smoke')
    with log.open('w', encoding='utf-8') as output:
        process = subprocess.Popen([args.java, '-Xmx512M', '-XX:ActiveProcessorCount=2', '-jar', 'BungeeCord.jar'],
                                   cwd=args.work_dir, stdin=subprocess.PIPE, stdout=output,
                                   stderr=subprocess.STDOUT, text=True, encoding='utf-8')
        try:
            ready(process, log)
            with sibling('udp-proxy-fixture').transport(forwarded, args.port) as port:
                args.udp_port = port
                for protocol in (763, 769):
                    expected = probes.tcp(args.port, protocol, False, smoke, forwarded)
                    actual = probes.udp(args, protocol, 'status')
                    assert actual['description'] == expected['description'], (expected, actual)
                    assert actual['players']['max'] == 42, actual
                    print('PASS Bungee TCP/RakNet status and pong', protocol, 'proxy=' + str(forwarded), flush=True)
        finally:
            stop(process)
    assert process.returncode == 0, log.read_text(encoding='utf-8')
    sibling('native-loader-smoke').check_failures(log)


def stop(process):
    """Stop only the owned Bungee process and enforce a bounded exit even after a failed assertion."""
    if process.poll() is not None:
        return
    process.stdin.write('end\n')
    process.stdin.flush()
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
        raise


def main():
    """Install a hash-pinned official Bungee build and test both listener modes using the exact plugin artifact."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--probe-dir', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--port', type=int, default=25892)
    args = parser.parse_args()
    args.work_dir = args.work_dir.resolve()
    args.probe_dir = args.probe_dir.resolve()
    plugins = args.work_dir / 'plugins'
    plugins.mkdir(parents=True, exist_ok=True)
    lock = json.loads((Path(__file__).parents[1] / 'proxy-versions.json').read_text(encoding='utf-8'))
    sibling('native-loader-smoke').download(args.work_dir / 'BungeeCord.jar', lock['bungee']['url'],
                                           lock['bungee']['sha512'], 'sha512')
    shutil.copy2(args.jar, plugins / 'raknetify2.jar')
    for forwarded in (False, True):
        run_case(args, forwarded)


if __name__ == '__main__':
    main()
