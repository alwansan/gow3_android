"""执行生产脚本的游戏输入校验段，确保 app0、eboot 摘要和派生镜像身份一致。"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/android/device/run-bionic.sh"


class BionicGameInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="gow3-bionic-inputs-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base_game = self.root / "base game"
        self.view = self.root / "merged game"
        for directory, data in ((self.base_game, b"base executable"), (self.view, b"update executable")):
            directory.mkdir()
            (directory / "eboot.bin").write_bytes(data)
        self.runtime = self.root / "runtime"
        (self.runtime / "data").mkdir(parents=True)
        (self.runtime / "data/patches.bin").write_bytes(b"G3PATCH2" + bytes(16))
        text = SCRIPT.read_text()
        start = text.index('if [ "${1:-game}" = game ]; then')
        self.production = text[start:text.index("\n# Bionic 库", start)]
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("GOW3_")}
        self.env["base"] = str(self.runtime)
        self.env["GOW3_GAME_DIR"] = str(self.base_game)
        if shutil.which("sha256sum") is None:
            tools = self.root / "tools"
            tools.mkdir()
            wrapper = tools / "sha256sum"
            wrapper.write_text('#!/bin/sh\nexec shasum -a 256 "$@"\n')
            wrapper.chmod(0o755)
            self.env["PATH"] = str(tools) + os.pathsep + self.env.get("PATH", "")

    def run_guard(self):
        return subprocess.run(
            ["bash", "-eu", "-c", self.production + '\nprintf "APP0=%s\\n" "$app0_dir"\n'],
            env=self.env, capture_output=True, text=True, timeout=10,
        )

    def expect_eboot(self, directory):
        self.env["GOW3_EBOOT_SHA256"] = hashlib.sha256((directory / "eboot.bin").read_bytes()).hexdigest()

    def test_base_without_update_keeps_existing_path(self):
        self.expect_eboot(self.base_game)
        result = self.run_guard()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"APP0={self.base_game}", result.stdout)

    def test_merged_view_identity_is_checked_instead_of_base(self):
        self.env["GOW3_APP0_DIR"] = str(self.view)
        self.expect_eboot(self.view)
        result = self.run_guard()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"APP0={self.view}", result.stdout)

    def test_base_identity_cannot_validate_different_merged_executable(self):
        self.env["GOW3_APP0_DIR"] = str(self.view)
        self.expect_eboot(self.base_game)
        result = self.run_guard()
        self.assertEqual(result.returncode, 2)
        self.assertIn("different game build", result.stderr)
        self.assertNotIn("APP0=", result.stdout)

    def test_missing_selected_view_does_not_fall_back_to_base(self):
        self.env["GOW3_APP0_DIR"] = str(self.root / "missing view")
        self.expect_eboot(self.base_game)
        result = self.run_guard()
        self.assertEqual(result.returncode, 2)
        self.assertIn("Game missing", result.stderr)


if __name__ == "__main__":
    unittest.main()
