"""Prüft das FW-004-Gate ausschließlich mit Pfaden in einem privaten Wegwerfverzeichnis."""

import os
import runpy
import tempfile
import unittest
from pathlib import Path


check = runpy.run_path(str(Path(__file__).with_name("check-isolation.py")))["check"]


class IsolationTest(unittest.TestCase):
    def test_only_physical_private_world_tree_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run = root / "run"
            run.mkdir(mode=0o700)
            world = run / "world"
            world.mkdir()
            control = root / "control"
            control.mkdir()
            (control / "marker").touch()
            check(run, world)
            with self.assertRaises(ValueError):
                check(run, control)
            link = world / "entry"
            link.symlink_to(control, target_is_directory=True)
            with self.assertRaises(ValueError):
                check(run, world)
            link.unlink()
            os.link(control / "marker", link)
            with self.assertRaises(ValueError):
                check(run, world)
            link.unlink()
            alias = root / "alias"
            alias.symlink_to(run, target_is_directory=True)
            with self.assertRaises(ValueError):
                check(alias, alias / "world")
            with self.assertRaises(FileNotFoundError):
                check(run, world / "missing")
            self.assertTrue((control / "marker").is_file())


if __name__ == "__main__":
    unittest.main()
