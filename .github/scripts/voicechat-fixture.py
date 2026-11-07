"""Exercise real Simple Voice Chat builds through the same UDP port as packaged Raknetify2 servers."""
import hashlib
import json
from pathlib import Path
import re
import socket
import struct
import time
import urllib.request
import uuid
import zipfile


def configure(folder, bukkit=False, public_port=None):
    """Opt an isolated server into shared sockets and retain voice's ordinary authentication and encryption."""
    config = folder / 'config'
    config.mkdir(parents=True, exist_ok=True)
    (config / 'raknetify2-voicechat.json').write_text('{"enabled":true}\n', encoding='utf-8')
    voice = folder / 'plugins/voicechat' if bukkit else config / 'voicechat'
    voice.mkdir(parents=True, exist_ok=True)
    destination = 'voice_host=127.0.0.1:' + str(public_port) + '\n' if public_port else ''
    (voice / 'voicechat-server.properties').write_text(
        'port=-1\nbind_address=127.0.0.1\nallow_pings=true\n' + destination, encoding='utf-8')


def install(folder, loader, public_port=None):
    """Install only the hash-pinned native loader or Bukkit voice artifact selected by the fixture."""
    lock = json.loads((Path(__file__).resolve().parents[2] / 'voicechat-native-lock.json').read_text())
    entry = lock[loader]
    destination = folder / ('plugins' if loader == 'bukkit' else 'mods') / 'voicechat.jar'
    with urllib.request.urlopen(entry['url'], timeout=60) as response:
        data = response.read()
    if hashlib.sha512(data).hexdigest() != entry['sha512']:
        raise ValueError("Artifact digest mismatch: " + str(entry['filename']))
    destination.write_bytes(data)
    configure(folder, loader == 'bukkit', public_port)


def client(folder, loader):
    """Install the real client with audio disabled, leaving its authentication and encrypted UDP exchange intact."""
    install(folder, loader)
    (folder / 'config/voicechat/voicechat-client.properties').write_text(
        'disabled=true\nmuted=true\nonboarding_finished=true\n', encoding='utf-8')


def has_ping(folder):
    """Detect the diagnostic protocol from the pinned artifact instead of expecting it in older voice releases."""
    files = list((folder / 'mods').glob('*voicechat*.jar')) + list((folder / 'plugins').glob('*voicechat*.jar'))
    assert len(files) == 1, 'Expected exactly one pinned Simple Voice Chat artifact'
    with zipfile.ZipFile(files[0]) as archive:
        return 'de/maxhenkel/voicechat/debug/PingHandler.class' in archive.namelist()


def await_ready(log, port):
    """Require the actual voice server to open its socket, not merely discover the integration entrypoint."""
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        text = log.read_text(encoding='utf-8', errors='replace')
        failures = ('Voice chat server error', 'Failed to run voice chat', 'No RakNet listener matches',
                    'Another voice plugin already supplies', 'Failed to load voice chat plugin')
        assert not any(message in text for message in failures), text[-6000:]
        if started(text, port):
            return
        time.sleep(0.05)
    raise AssertionError('Simple Voice Chat did not open the shared listener')


def started(text, port):
    """Recognize the old and current native startup messages only after our exact socket provider was selected."""
    if 'Using custom voicechat socket implementation: com.ishland.raknetify.common.voice.SharedVoiceSocket' not in text:
        return False
    return any(('VoiceChatServerThread' in line or '[voicechat]' in line)
               and 'server started at' in line.lower() and re.search(r'\b' + str(port) + r'\b', line)
               for line in text.splitlines())


def expect_silence(channel, data):
    """Reject unauthorized or oversized voice input without treating a socket error as successful rejection."""
    channel.send(data)
    channel.settimeout(0.25)
    try:
        reply = channel.recv(8192)
    except socket.timeout:
        return
    raise AssertionError('Unexpected voice response to invalid input: ' + reply.hex())


def ping(channel, nonce):
    """Use the upstream diagnostic wire protocol and require its exact unframed reply from the shared source port."""
    payload = nonce.bytes + struct.pack('>q', 123456789)
    request = b'\xff' + uuid.UUID('58bc9ae9-c7a8-45e4-a11c-efbb67199425').bytes + b'\x18' + payload
    channel.settimeout(3)
    channel.send(request)
    assert channel.recv(8192) == payload, 'Voice ping bytes changed on the shared transport'
    return request


def check(folder, log, backend_port, public_port):
    """Check native socket startup, unauthenticated rejection and available voice replies alongside RakNet status."""
    await_ready(log, backend_port)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as channel:
        channel.connect(('127.0.0.1', public_port))
        expect_silence(channel, b'\xff' + uuid.UUID('00000000-0000-0000-0000-000000000001').bytes + b'\x01\x00')
        if has_ping(folder):
            request = ping(channel, uuid.UUID('00000000-0000-0000-0000-000000000002'))
            expect_silence(channel, request.ljust(4096, b'\x00'))
            ping(channel, uuid.UUID('00000000-0000-0000-0000-000000000003'))
            print('PASS native voice ping and oversized/unknown-secret rejection on shared UDP port', flush=True)
        else:
            print('PASS legacy voice API startup and unknown-secret rejection on shared UDP port', flush=True)
