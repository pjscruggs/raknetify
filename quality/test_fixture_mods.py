"""Keep repeated runtime checks bound to the supplied artifacts without deleting unexpected local files."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


def load_smoke():
    """Load the server preparation helpers without starting Minecraft or requesting downloads."""
    path = Path(__file__).resolve().parents[1] / '.github/scripts/server-smoke.py'
    spec = importlib.util.spec_from_file_location('fixture_mod_smoke', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FixtureModsTest(unittest.TestCase):
    """Cover stale builds, unowned duplicates and altered files in reused server directories."""

    def test_repeated_run_removes_previous_artifacts(self):
        """Replace a recorded old build and its dependencies with exactly the next run's selected files."""
        smoke = load_smoke()
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            smoke.reset_fixture_mods(folder)
            (folder / 'mods/old.jar').write_bytes(b'old mod')
            (folder / 'mods/dependency.jar').write_bytes(b'old dependency')
            receipt = folder / 'fixture-mods.json'
            receipt.write_text(json.dumps(smoke.fixture_mod_inventory(folder)))
            smoke.reset_fixture_mods(folder)
            (folder / 'mods/new.jar').write_bytes(b'new mod')
            self.assertEqual(set(smoke.fixture_mod_inventory(folder)), {'new.jar'})

    def test_unowned_duplicates_are_preserved_and_rejected(self):
        """Refuse the old-and-new JAR combination that previously let Fabric select an obsolete build."""
        smoke = load_smoke()
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / 'mods').mkdir()
            (folder / 'mods/old.jar').write_bytes(b'old mod')
            (folder / 'mods/new.jar').write_bytes(b'new mod')
            before = smoke.fixture_mod_inventory(folder)
            with self.assertRaises(ValueError):
                smoke.reset_fixture_mods(folder)
            self.assertEqual(smoke.fixture_mod_inventory(folder), before)

    def test_modified_owned_file_is_preserved_and_rejected(self):
        """A previous filename alone never authorizes deleting a file changed outside fixture preparation."""
        smoke = load_smoke()
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            smoke.reset_fixture_mods(folder)
            mod = folder / 'mods/owned.jar'
            mod.write_bytes(b'original')
            (folder / 'fixture-mods.json').write_text(json.dumps(smoke.fixture_mod_inventory(folder)))
            mod.write_bytes(b'changed')
            with self.assertRaises(ValueError):
                smoke.reset_fixture_mods(folder)
            self.assertEqual(mod.read_bytes(), b'changed')
