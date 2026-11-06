"""Prove that parallel compatibility jobs retain every version from the actual dependency locks exactly once."""
from collections import Counter
import importlib.util
import json
from pathlib import Path
import unittest


def server_module():
    """Load the version partitioner without launching Minecraft or downloading any dependency."""
    path = Path(__file__).resolve().parents[1] / '.github/scripts/server-smoke.py'
    spec = importlib.util.spec_from_file_location('server_shards', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ServerShardTests(unittest.TestCase):
    """Keep CI partitioning equivalent to the complete sequential test list as new versions enter the locks."""

    def test_actual_compatibility_locks_are_covered_once(self):
        """Check the exact Krypton and PacketFixer inventories, including ordering and balanced runner assignments."""
        partition = server_module().shard_versions
        folder = Path(__file__).resolve().parents[1] / '.github'
        for name in ('krypton', 'packetfixer'):
            versions = list(json.loads((folder / (name + '-versions.json')).read_text(encoding='utf-8')))
            parts = [partition(versions, index, 4) for index in range(1, 5)]
            flattened = [version for part in parts for version in part]
            self.assertEqual(Counter(versions), Counter(flattened))
            lengths = [len(part) for part in parts]
            self.assertLessEqual(max(lengths) - min(lengths), 1)
            for part in parts:
                self.assertEqual(part, sorted(part, key=versions.index))

    def test_default_and_explicit_version_order_are_preserved(self):
        """Preserve an unsharded selection and partition the caller's explicit order without sorting versions."""
        partition = server_module().shard_versions
        versions = ['26.3', '1.18', '26.1', '1.21.11', '26.2']
        self.assertEqual(versions, partition(versions, 1, 1))
        self.assertEqual(['26.3', '26.1', '26.2'], partition(versions, 1, 2))
        self.assertEqual(['1.18', '1.21.11'], partition(versions, 2, 2))

    def test_invalid_or_empty_shards_fail(self):
        """Reject zero, negative, out-of-range, oversized, and empty selections instead of passing an untested job."""
        partition = server_module().shard_versions
        for shard, count in ((0, 2), (-1, 2), (3, 2), (1, 0), (1, 3)):
            with self.assertRaises(ValueError):
                partition(['1.18', '26.3'], shard, count)
        with self.assertRaises(ValueError):
            partition([], 1, 1)

    def test_duplicate_versions_fail(self):
        """Prevent a repeated explicit version from silently being tested twice on different runners."""
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            server_module().shard_versions(['26.3', '26.3'], 1, 2)


if __name__ == '__main__':
    unittest.main()
