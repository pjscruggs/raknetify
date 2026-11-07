"""Guard proxy smoke tests against TCP readiness racing asynchronous UDP listener creation."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, patch


class ProxyReadinessTests(unittest.TestCase):
    """Require both listener milestones without starting a proxy or waiting on wall-clock time."""

    def setUp(self):
        """Load the actual runner and supply a live process with a controlled growing log."""
        spec = importlib.util.spec_from_file_location(
            'proxy_readiness_fixture',
            Path(__file__).resolve().parents[1] / '.github/scripts/proxy-smoke.py')
        self.runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runner)
        self.process = Mock()
        self.process.poll.return_value = None
        self.log = Mock()

    def test_tcp_ready_does_not_start_udp_probe(self):
        """A completed proxy startup still waits until the separate RakNet bind reports success."""
        self.log.read_text.side_effect = [
            'Done (1s)!', 'Done (1s)!\nRaknetify server started on /127.0.0.1:25565']
        with patch.object(self.runner.time, 'sleep') as pause:
            self.runner.ready(self.process, self.log)
        pause.assert_called_once_with(0.25)
        self.assertEqual(self.process.poll.call_count, 2)

    def test_missing_udp_listener_reaches_deadline(self):
        """TCP success cannot conceal a failed plugin bootstrap or a UDP listener that never binds."""
        self.log.read_text.return_value = 'Done (1s)!'
        with (patch.object(self.runner.time, 'monotonic', side_effect=[0, 1, 61]),
              patch.object(self.runner.time, 'sleep'), self.assertRaises(TimeoutError)):
            self.runner.ready(self.process, self.log)


if __name__ == '__main__':
    unittest.main()
