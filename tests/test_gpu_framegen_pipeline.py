"""编译运行生产辅助逻辑；不以宿主 fake 测试冒充真实 Vulkan 驱动验收。"""
import os
import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class GpuFramegenPipelineTest(unittest.TestCase):
    def test_production_helpers(self):
        with tempfile.TemporaryDirectory(prefix="gow3-framegen-") as directory:
            binary = Path(directory) / "pipeline-test"
            command = shlex.split(os.environ.get("CXX", "c++")) + [
                "-std=c++23", "-Wall", "-Wextra", "-Werror",
                "-I", str(ROOT / "gpu/shadps4"),
                "-I", str(ROOT / "third_party/FEX/External/Vulkan-Headers/include"),
                str(ROOT / "tests/test_gpu_framegen_pipeline.cpp"), "-o", str(binary),
            ]
            subprocess.run(command, check=True, capture_output=True, text=True, timeout=120)
            result = subprocess.run([str(binary)], check=True, capture_output=True,
                                    text=True, timeout=20)
            self.assertIn("PASS", result.stdout)


if __name__ == "__main__":
    unittest.main()
