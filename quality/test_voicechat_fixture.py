"""Keep native voice startup checks compatible with loader-specific logging without accepting unrelated listeners."""
import importlib.util
from pathlib import Path
import unittest


def voice_fixture():
    """Load the isolated server fixture without starting a server or downloading a mod."""
    path = Path(__file__).resolve().parents[1] / '.github/scripts/voicechat-fixture.py'
    spec = importlib.util.spec_from_file_location('voicechat_fixture', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class VoiceStartupTests(unittest.TestCase):
    """Check real Fabric and Bukkit startup formats and reject incomplete shared-socket startup."""

    def test_supported_log_formats(self):
        """Accept old Fabric, modern Fabric and Paper messages only with our selected socket and exact port."""
        fixture = voice_fixture()
        selected = ('Using custom voicechat socket implementation: '
                    'com.ishland.raknetify.common.voice.SharedVoiceSocket\n')
        messages = ('[VoiceChatServerThread/INFO]: Server started at port 28990',
                    '[VoiceChatServerThread/INFO]: [voicechat] Voice chat server started at 127.0.0.1:28990',
                    '[10:09:06 INFO]: [voicechat] Voice chat server started at 127.0.0.1:28990')
        for message in messages:
            with self.subTest(message=message):
                self.assertTrue(fixture.started(selected + message, 28990))
                self.assertFalse(fixture.started(message, 28990))
                self.assertFalse(fixture.started(selected + message, 2899))
        self.assertFalse(fixture.started(selected, 28990))
        self.assertFalse(fixture.started(selected + 'Other server started at 28990', 28990))
