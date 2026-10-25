"""Regression contracts for Android SaveData/VFS mounting and APK identity."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_ID = "com.alwansan.gow3.android"


class AndroidSaveDataContractTests(unittest.TestCase):
    def test_vfs_has_room_for_all_save_slots(self):
        vfs = (ROOT / "src/runtime_file.c").read_text(encoding="utf-8")
        savedata = (ROOT / "src/runtime_savedata.c").read_text(encoding="utf-8")
        max_mounts = int(re.search(r"#define MAX_MOUNTS ([0-9]+)", vfs).group(1))
        slots = int(re.search(r"#define SLOTS ([0-9]+)", savedata).group(1))
        # /app0, /hostapp, /temp0, /download0, and /data are always present.
        self.assertGreaterEqual(max_mounts, slots + 5)

    def test_save_mount_failure_is_returned_to_the_guest(self):
        savedata = (ROOT / "src/runtime_savedata.c").read_text(encoding="utf-8")
        self.assertIn("if (runtime_file_mount(result->point.data,host)!=0)", savedata)
        self.assertIn("return ERR_MOUNT_FULL;", savedata)

    def test_metadata_is_written_atomically_and_checked(self):
        savedata = (ROOT / "src/runtime_savedata.c").read_text(encoding="utf-8")
        self.assertIn('"%s/param.bin.tmp"', savedata)
        self.assertIn("fflush(f)", savedata)
        self.assertIn("fsync(fileno(f))", savedata)
        self.assertIn("if (write_param(slots[slot].meta,&p))", savedata)

    def test_missing_stat_and_access_paths_are_diagnosable(self):
        vfs = (ROOT / "src/runtime_file.c").read_text(encoding="utf-8")
        self.assertIn("Runtime: stat(%s -> %s) -> not found", vfs)
        self.assertIn("Runtime: access(%s -> %s) -> not found", vfs)

    def test_version_name_is_compatible_with_update_checker(self):
        gradle = (ROOT / "android-launcher/app/build.gradle.kts").read_text(encoding="utf-8")
        version = re.search(r'versionName = "([^"]+)"', gradle).group(1)
        self.assertRegex(version, r"^\\d+(?:\\.\\d+)*-bionic-v\\d+$")
        self.assertIn('versionCode = 2010705', gradle)

    def test_package_id_matches_runtime_manifest_and_apk_validator(self):
        gradle = (ROOT / "android-launcher/app/build.gradle.kts").read_text(encoding="utf-8")
        manifest = json.loads((ROOT / "tools/android/runtime/runtime-manifest.bionic.json").read_text(encoding="utf-8"))
        validator = (ROOT / "tools/android/apk/validate_release_apk.py").read_text(encoding="utf-8")
        build = (ROOT / "tools/android/apk/build_release_apk.sh").read_text(encoding="utf-8")
        self.assertIn(f'applicationId = "{APP_ID}"', gradle)
        self.assertEqual(manifest["application_id"], APP_ID)
        self.assertIn(f"APPLICATION_ID = '{APP_ID}'", validator)
        self.assertIn(APP_ID, build)


if __name__ == "__main__":
    unittest.main()
