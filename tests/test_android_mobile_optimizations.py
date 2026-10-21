"""执行生产代码与 ARM64 指令；宿主测试不替代真机 Vulkan 性能验收。

SRT、缓存、区间测试改编自 Daedalus 239b7df (erickmendes22)。
"""
import os
import random
import shlex
import shutil
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path

try:
    import unicorn
    from unicorn import arm64_const as arm
except ImportError:
    unicorn = None

ROOT = Path(__file__).resolve().parents[1]
INCLUDES = ROOT / "gpu/shadps4"


def checked(command, **kwargs):
    result = subprocess.run(command, capture_output=True, timeout=120, **kwargs)
    if result.returncode:
        raise AssertionError(f"{command}: {result.stderr!r}\n{result.stdout!r}")
    return result


def build(source, directory, name, extra=()):
    cpp = Path(directory) / (name + ".cpp")
    exe = Path(directory) / name
    cpp.write_text(source)
    checked(shlex.split(os.environ.get("CXX", "c++")) + [
        "-std=c++23", "-O2", "-Wall", "-Wextra", "-Werror", *extra,
        "-I", str(INCLUDES), "-I", str(ROOT / "gpu/shim"), str(cpp), "-o", str(exe)])
    return exe


@unittest.skipUnless(shutil.which("c++") and os.name == "posix", "POSIX C++ compiler required")
class AndroidMobileOptimizationTest(unittest.TestCase):
    def test_android_disabled_controls_bypass_legacy_fsr(self):
        source = r'''
#include <cassert>
#include <fstream>
#include "gow3_graphics.h"
#include "gow3_settings.h"
int main(int argc, char** argv) {
    assert(argc == 2);
    using namespace Gow3Graphics;
    for (bool android : {false, true}) {
        for (bool upscaler : {false, true}) {
            for (bool sharpen : {false, true}) {
                for (bool fsr1 : {false, true}) {
                    for (bool rcas : {false, true}) {
                        const auto policy = ResolveLegacyFsrOptions(android, upscaler, sharpen, fsr1, rcas);
                        if (!android) {
                            assert(policy.fsr1 == fsr1 && policy.rcas == rcas);
                        } else if (!upscaler) {
                            assert(!policy.fsr1 && !policy.rcas);
                            assert(!FsrPassRuns(policy.fsr1, policy.rcas, 960, 540, 960, 540));
                            assert(!FsrPassRuns(policy.fsr1, policy.rcas, 960, 540, 1280, 720));
                            assert(!FsrPassRuns(policy.fsr1, policy.rcas, 1920, 1080, 960, 540));
                        } else {
                            assert(policy.fsr1 == fsr1);
                            assert(policy.rcas == (rcas && sharpen));
                        }
                    }
                }
            }
        }
    }
    unsetenv("GOW3_ANDROID_INPUT"); unsetenv("GOW3_ANDROID_PORT");
    unsetenv("GOW3_UPSCALER"); unsetenv("GOW3_FSR1"); unsetenv("GOW3_RCAS");
    setenv("GOW3_CONFIG", argv[1], 1);
    std::ofstream(argv[1]) << "";
    auto& settings = Gow3Settings::Get();
    Gow3Settings::Load();
    assert(settings.rcas && settings.sharpen);
    setenv("GOW3_ANDROID_INPUT", "1", 1);
    Gow3Settings::Load();
    assert(settings.upscaler == Gow3Settings::UpscalerOff);
    assert(!settings.sharpen && !settings.fsr1 && !settings.rcas);
    std::ofstream(argv[1]) << "upscaler=off\nsharpen=0\nfsr1=1\nrcas=1\n";
    Gow3Settings::Load();
    assert(settings.fsr1 && settings.rcas);
    const auto current = [&] {
        return ResolveLegacyFsrOptions(true, settings.upscaler != Gow3Settings::UpscalerOff,
                                       settings.sharpen, settings.fsr1, settings.rcas);
    };
    auto policy = current();
    assert(!policy.fsr1 && !policy.rcas);
    setenv("GOW3_FSR1", "1", 1); setenv("GOW3_RCAS", "1", 1);
    Gow3Settings::Load();
    policy = current();
    assert(!policy.fsr1 && !policy.rcas);
    settings.upscaler = Gow3Settings::UpscalerFsr3;
    settings.sharpen = true;
    policy = current();
    assert(policy.fsr1 && policy.rcas);
    settings.sharpen = false;
    policy = current();
    assert(!policy.rcas);
    settings.upscaler = Gow3Settings::UpscalerOff;
    policy = current();
    assert(!policy.fsr1 && !policy.rcas);
}
'''
        with tempfile.TemporaryDirectory() as directory:
            exe = build(source, directory, "android_postprocess",
                        (str(ROOT / "gpu/shim/gow3_settings.cpp"),))
            checked([str(exe), str(Path(directory) / "settings.ini")])

    def test_android_postprocess_policy_gates_creation_and_recording(self):
        cpp = (INCLUDES / "video_core/renderer_vulkan/vk_presenter.cpp").read_text()
        start = cpp.index("    const auto legacy_fsr = Gow3Graphics::ResolveLegacyFsrOptions(")
        end = cpp.index("    // Vulkan has no sRGB", start)
        body = cpp[start:end]
        self.assertIn("fsr_settings.enable = legacy_fsr.fsr1;", body)
        self.assertIn("fsr_settings.use_rcas = legacy_fsr.rcas;", body)
        self.assertLess(body.index("if (fsr_runs) {"), body.index("fsr_pass.Create("))
        self.assertLess(body.index("if (fsr_runs) {"), body.index("fsr_pass.Render("))
        defaults = (ROOT / "tools/android/config/gow3-default.ini").read_text().splitlines()
        for key in ("upscaler=off", "sharpen=0", "fsr1=0", "rcas=0"):
            self.assertIn(key, defaults)

    def test_empty_readbacks_skip_host_wait_but_still_retire(self):
        cpp = (INCLUDES / "video_core/texture_cache/texture_cache.cpp").read_text()
        body = cpp[cpp.index("void TextureCache::ProcessDownloadImages()"):]
        start = body.index("    if (deferred_readbacks) {")
        end = body.index("        // Upload workers", start)
        source = r'''
#include <atomic>
#include <cassert>
#include <mutex>
#include <vector>
#include "gow3_mobile_policy.h"
struct Cache {
    bool deferred_readbacks = true;
    std::atomic<bool> has_pending_readbacks{false};
    std::mutex download_images_mutex, readback_mutex;
    std::vector<int> download_images;
    unsigned retired = 0, waits = 0;
    void RetireReadbacks() { ++retired; }
    void Process() {
ACTUAL
        ++waits;
    }
    }
};
int main(int argc, char** argv) {
    assert(argc == 2);
    setenv("GOW3_READBACK_EMPTY_FAST", argv[1], 1);
    const bool fast = *argv[1] == '1';
    Cache cache;
    cache.Process(); assert(cache.waits == unsigned(!fast));
    cache.waits = 0; cache.has_pending_readbacks = true;
    cache.Process(); assert(cache.waits == unsigned(!fast));
    assert(cache.retired == unsigned(fast));
    cache.waits = 0; cache.download_images.push_back(1);
    cache.Process(); assert(cache.waits == 1);
}
'''.replace("ACTUAL", body[start:end])
        destructor = cpp[cpp.index("TextureCache::~TextureCache()"):cpp.index("template <bool track>")]
        self.assertIn("scheduler.Finish()", destructor)
        self.assertNotIn("GOW3_READBACK_EMPTY_FAST", destructor)
        with tempfile.TemporaryDirectory() as directory:
            exe = build(source, directory, "readbacks")
            for mode in ("0", "1"):
                checked([str(exe), mode])

    def test_submission_bound_and_legacy_rollback(self):
        cpp = (INCLUDES / "video_core/renderer_vulkan/vk_scheduler.cpp").read_text()
        body = cpp[cpp.index("void Scheduler::SubmitExecution("):]
        start = body.index("    const u64 signal_value")
        end = body.index("#if TRACY_GPU_ENABLED", start)
        self.assertIn("if (!bounded_submit) work_semaphore.Refresh();", body)
        source = r'''
#include <cassert>
#include <vector>
#include "gow3_mobile_policy.h"
using u64 = unsigned long long;
struct Scheduler {
    struct Semaphore {
        u64 tick = 0;
        std::vector<u64> waited;
        unsigned polls = 0;
        u64 NextTick() { return ++tick; }
        void Wait(u64 value) { assert(value < tick); waited.push_back(value); }
        void Refresh() { ++polls; }
    } work_semaphore;
    void Submit() {
ACTUAL
        if (!bounded_submit) work_semaphore.Refresh();
    }
};
int main(int argc, char** argv) {
    assert(argc == 2);
    setenv("GOW3_ANDROID_INPUT", "1", 1);
    setenv("GOW3_VK_POLL_EACH_SUBMIT", argv[1], 1);
    Scheduler scheduler;
    for (unsigned i = 1; i <= 10; ++i) scheduler.Submit();
    if (*argv[1] == '1') {
        assert(scheduler.work_semaphore.waited.empty());
        assert(scheduler.work_semaphore.polls == 10);
    } else {
        assert((scheduler.work_semaphore.waited == std::vector<u64>{1, 2}));
        assert(scheduler.work_semaphore.polls == 0);
    }
}
'''.replace("ACTUAL", body[start:end])
        with tempfile.TemporaryDirectory() as directory:
            exe = build(source, directory, "submissions")
            for mode in ("0", "1"):
                checked([str(exe), mode])

    def test_mobile_defaults_and_explicit_frame_queue(self):
        source = r'''
#include <cassert>
#include <fstream>
#include "gow3_mobile_policy.h"
#include "gow3_settings.h"
int main(int argc, char** argv) {
    assert(argc == 2);
    using namespace Gow3Mobile;
    unsetenv("GOW3_ANDROID_INPUT"); unsetenv("GOW3_ANDROID_PORT");
    unsetenv("GOW3_FRAMES_AHEAD"); unsetenv("GOW3_GPU_COMPILER_WORKERS");
    assert(!Android() && FramesQueued(false) == 1 && FramesQueued(true) == 2);
    assert(PreparationWorkers(true, 8) == 0 && PreparationWorkers(false, 8) == 8);
    assert(CompilerWorkers(true, 6) == 2 && CaptureWorkers(true, 2) == 1);
    assert(CompilerWorkers(false, 6) == 6 && CaptureWorkers(false, 2) == 2);
    assert(WorkerOverride("GOW3_GPU_COMPILER_WORKERS", 2) == 2);
    for (const char* invalid : {"", "0", "7", "-1", "oops", "2x"}) {
        setenv("GOW3_GPU_COMPILER_WORKERS", invalid, 1);
        assert(WorkerOverride("GOW3_GPU_COMPILER_WORKERS", 2) == 2);
    }
    setenv("GOW3_GPU_COMPILER_WORKERS", "6", 1);
    assert(WorkerOverride("GOW3_GPU_COMPILER_WORKERS", 2) == 6);
    assert(!WaitHostCopies(false, true));
    assert(WaitHostCopies(true, true) && WaitHostCopies(false, false));
    for (unsigned i = 1; i <= 8; ++i) assert(BoundedTick(i) == 0);
    assert(BoundedTick(9) == 1 && BoundedTick(100) == 92);
    assert(!Enabled("GOW3_TEST_UNSET", false));
    setenv("GOW3_TEST_UNSET", "0", 1); assert(!Enabled("GOW3_TEST_UNSET"));
    setenv("GOW3_TEST_UNSET", "1", 1); assert(Enabled("GOW3_TEST_UNSET", false));
    setenv("GOW3_CONFIG", argv[1], 1);
    std::ofstream(argv[1]) << "fps_limit=60\n";
    Gow3Settings::Load(); assert(Gow3Settings::Get().frames_queued == 1);
    setenv("GOW3_ANDROID_INPUT", "1", 1);
    Gow3Settings::Load(); assert(Gow3Settings::Get().frames_queued == 2);
    for (unsigned i : {0, 1, 2}) {
        std::ofstream(argv[1]) << "frames_queued=" << i << '\n';
        Gow3Settings::Load(); assert(Gow3Settings::Get().frames_queued == int(i));
    }
    setenv("GOW3_FRAMES_AHEAD", "0", 1);
    Gow3Settings::Load(); assert(Gow3Settings::Get().frames_queued == 0);
    unsetenv("GOW3_FRAMES_AHEAD"); unsetenv("GOW3_ANDROID_INPUT");
    setenv("GOW3_ANDROID_PORT", "1", 1); assert(Android());
}
'''
        with tempfile.TemporaryDirectory() as directory:
            exe = build(source, directory, "mobile",
                        (str(ROOT / "gpu/shim/gow3_settings.cpp"),))
            checked([str(exe), str(Path(directory) / "settings.ini")])

    def test_native_fault_falls_back_and_keeps_cache_layout(self):
        cpp = (INCLUDES / "shader_recompiler/ir/passes/flatten_extended_userdata_pass.cpp").read_text()
        start = cpp.index("struct NativeSrtCode {")
        end = cpp.index("\n} // namespace Shader", cpp.index("void RunSrtProgram(", start))
        source = r'''
#include <atomic>
#include <vector>
#include <memory>
#include <mutex>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <cassert>
#include <csignal>
#include <csetjmp>
#include <cstdio>
#include <sys/mman.h>
#include <unistd.h>
#include "shader_recompiler/ir/passes/srt_arm64.h"
#define ARCH_ARM64 1
#define LOG_WARNING(...) ((void)0)
#define LOG_ERROR(...) ((void)0)
#define UNREACHABLE_MSG(...) std::abort()
using u8 = std::uint8_t;
using u32 = std::uint32_t;
using u64 = std::uint64_t;
thread_local sigjmp_buf* runtime_fault_recover = nullptr;
using namespace Shader::Arm64Srt;
namespace Shader {
struct SrtProgram;
struct SrtWalker { SrtProgram* program{}; };
using PFN_SrtWalker = SrtWalker;
static_assert(sizeof(SrtWalker) == sizeof(void*));
void RunSrtProgram(const u32*, const u32*, u32*);
ACTUAL
}
static int calls;
void failing(const u32*, u32* flat) { ++calls; flat[16] = 999; std::raise(SIGSEGV); }
void working(const u32* data, u32* flat) { ++calls; flat[16] = data[4]; }
int main(int argc, char** argv) {
    using namespace Shader;
    assert(argc == 2);
    setenv("GOW3_SRT_NATIVE", argv[1], 1);
    struct sigaction action{};
    action.sa_handler = [](int) { assert(runtime_fault_recover); siglongjmp(*runtime_fault_recover, 1); };
    action.sa_flags = SA_NODEFER;
    sigemptyset(&action.sa_mask);
    sigaction(SIGSEGV, &action, nullptr); sigaction(SIGBUS, &action, nullptr);
    u32 data[32]{}, flat[32]{}; data[4] = 33;
    SrtProgram stored{{SrtMagic, OpCopyImm, 4, 16, OpEnd}, nullptr};
    NativeSrtCode native; native.function = failing; stored.native = &native;
    sigjmp_buf previous; runtime_fault_recover = &previous;
    RunSrtWalker(&stored, data, flat);
    assert(flat[16] == 33 && calls == 1 && !native.enabled);
    assert(runtime_fault_recover == &previous);
    RunSrtWalker(&stored, data, flat); assert(calls == 1);
    native.enabled = true; native.function = working;
    RunSrtWalker(&stored, data, flat); assert(calls == 2 && flat[16] == 33);
    assert(runtime_fault_recover == &previous);
    assert(std::memcmp(SrtProgramCode(&stored), stored.words.data(), stored.words.size()*4) == 0);
    auto loaded = RegisterWalkerCode(reinterpret_cast<const u8*>(stored.words.data()), stored.words.size()*4);
    assert(loaded.program && bool(loaded.program->native) == (*argv[1] == '1'));
    assert(std::memcmp(SrtProgramCode(loaded.program), stored.words.data(), stored.words.size()*4) == 0);
    stored.words = {SrtMagic, OpPushImm, 0, OpCopyImm, 0, 16, OpPop, OpEnd};
    data[0] = data[1] = 0;
    stored.native = nullptr;
    RunSrtWalker(&stored, data, flat);
    assert(flat[16] == 0 && (stored.words[3] & OpZeroed));
    assert(runtime_fault_recover == &previous);
}
'''.replace("ACTUAL", cpp[start:end])
        with tempfile.TemporaryDirectory() as directory:
            compiler = checked(["c++", "--version"]).stdout.lower()
            extra = () if b"clang" in compiler else ("-Wno-clobbered",)
            exe = build(source, directory, "fallback", extra)
            for mode in ("0", "1"):
                checked([str(exe), mode])

    @unittest.skipUnless(unicorn, "Unicorn ARM64 execution engine required")
    def test_native_srt_instructions(self):
        source = r'''
#include <iostream>
#include "shader_recompiler/ir/passes/srt_arm64.h"
int main() {
    std::vector<std::uint32_t> words;
    std::uint32_t value;
    while (std::cin.read(reinterpret_cast<char*>(&value), 4)) words.push_back(value);
    auto out = Shader::Arm64Srt::Compile(words);
    std::cout.write(reinterpret_cast<const char*>(out.data()), std::streamsize(out.size() * 4));
}
'''
        with tempfile.TemporaryDirectory() as directory:
            exe = build(source, directory, "srt")

            def run(ops, flat=None, userdata=None, memory=None):
                data = struct.pack("<" + "I" * (len(ops) + 2), 0x42545253, *ops, 0)
                code = checked([str(exe)], input=data).stdout
                self.assertTrue(code)
                uc = unicorn.Uc(unicorn.UC_ARCH_ARM64, unicorn.UC_MODE_ARM)
                for address in (0x10000, 0x20000, 0x30000, 0x40000):
                    uc.mem_map(address, 0x10000)
                uc.mem_write(0x10000, code)
                uc.mem_write(0x20000, struct.pack("<256I", *(userdata or [0] * 256)))
                uc.mem_write(0x30000, struct.pack("<256I", *(flat or [0] * 256)))
                if memory:
                    for address, blob in memory.items():
                        uc.mem_write(address, blob)
                uc.reg_write(arm.UC_ARM64_REG_X0, 0x20000)
                uc.reg_write(arm.UC_ARM64_REG_X1, 0x30000)
                uc.reg_write(arm.UC_ARM64_REG_SP, 0x4f000)
                uc.reg_write(arm.UC_ARM64_REG_LR, 0x18000)
                for reg in range(19, 30):
                    uc.reg_write(getattr(arm, "UC_ARM64_REG_X" + str(reg)), reg * 99)
                uc.emu_start(0x10000, 0x18000, count=100000)
                self.assertEqual(uc.reg_read(arm.UC_ARM64_REG_PC), 0x18000)
                self.assertEqual(uc.reg_read(arm.UC_ARM64_REG_SP), 0x4f000)
                for reg in range(19, 30):
                    self.assertEqual(uc.reg_read(getattr(arm, "UC_ARM64_REG_X" + str(reg))), reg * 99)
                self.last_srt_value = struct.unpack("<I", bytes(uc.mem_read(0x4f000 - 128, 4)))[0]
                return list(struct.unpack("<256I", bytes(uc.mem_read(0x30000, 1024))))

            rng = random.Random(713)
            for opcode in range(9, 20):
                if opcode == 17:
                    continue
                for a, b in [(0, 0), (0xffffffff, 32), (0x80000000, 31)] + [
                        (rng.getrandbits(32), rng.getrandbits(32)) for _ in range(5)]:
                    values = {9: a + b, 10: a - b, 11: a * b, 12: a << (b & 31),
                              13: a >> (b & 31), 14: a & b, 15: a | b, 16: a ^ b,
                              18: min(a, b), 19: max(a, b)}
                    expected = values[opcode] & 0xffffffff
                    self.assertEqual(run([8, a, 8, b, opcode, 0x80000005, 40])[40], 0)
                    self.assertEqual(self.last_srt_value, expected)
                    self.assertEqual(run([8, a, 8, b, opcode, 8, 255, 14, 5, 41],
                                         userdata=list(range(256)))[41], expected & 255)
            self.assertEqual(run([8, 0xfffffffa, 17, 5, 42], userdata=list(range(256)))[42], 5)
            self.assertEqual(run([8, 0xabcdef01, 8, 8, 8, 12, 20, 8, 255, 14, 5, 43],
                                 userdata=list(range(256)))[43], 0xef)
            user = [0] * 256
            user[0], user[1] = 0x21000, 0xabcd0000
            memory = {0x21000: struct.pack("<4I", 99, 0x22000, 0, 77),
                      0x22000: struct.pack("<2I", 123, 456)}
            flat = [0] * 256
            flat[2] = 1
            result = run([1, 0, 4, 0, 50, 6, 2, 2, 4, 1, 51, 3, 7, 2, 5, 52, 3],
                         flat=flat, userdata=user, memory=memory)
            self.assertEqual([result[i] for i in (50, 51, 52)], [99, 456, 0x22000])
            self.assertEqual(run([0x80000001, 0, 0x80000004, 9999, 60, 3])[60], 0)
            for invalid in ([0x42545253, 3, 0], [0x42545253, 8, 1],
                            [0x42545253, 8, 1, 0], [0x42545253, 99, 0]):
                self.assertFalse(checked([str(exe)],
                    input=struct.pack("<" + "I" * len(invalid), *invalid)).stdout)

    def test_cache_identity_corruption_and_atomic_replace(self):
        source = r'''
#include <cassert>
#include "video_core/renderer_vulkan/driver_cache_file.h"
int main(int argc, char** argv) {
    assert(argc == 2);
    using namespace Vulkan::DriverCacheFile;
    Identity id{0x5143, 0x44050001, 123, {1,2,3}};
    std::vector<std::uint8_t> payload(64);
    std::uint32_t header[]{32, 1, id.vendor, id.device};
    std::memcpy(payload.data(), header, 16);
    std::memcpy(payload.data() + 16, id.uuid.data(), 16);
    auto encoded = Encode(payload, id);
    assert(Decode(encoded, id) == payload);
    auto other = id; other.driver++; assert(Decode(encoded, other).empty());
    other = id; other.uuid[0]++; assert(Decode(encoded, other).empty());
    other = id; other.device++; assert(Decode(encoded, other).empty());
    other = id; other.vendor++; assert(Decode(encoded, other).empty());
    encoded.back() ^= 1; assert(Decode(encoded, id).empty());
    encoded.pop_back(); assert(Decode(encoded, id).empty());
    assert(Decode(std::span<const std::uint8_t>{}, id).empty());
    auto file = std::filesystem::path(argv[1]) / "cache" / "0.bin";
    assert(Save(file, payload, id)); assert(Load(file, id) == payload);
    payload.back()++; assert(Save(file, payload, id)); assert(Load(file, id) == payload);
    assert(!std::filesystem::exists(file.string() + ".tmp"));
    assert(!Save(file, std::span<const std::uint8_t>{}, id));
    assert(Load(file, id) == payload);
}
'''
        with tempfile.TemporaryDirectory() as directory:
            exe = build(source, directory, "cache_test")
            checked([str(exe), directory])

    def test_cache_checkpoint_rejects_incomplete_data_and_stops_worker(self):
        cpp = (INCLUDES / "video_core/renderer_vulkan/vk_pipeline_cache.cpp").read_text()
        start = cpp.index("struct PipelineCache::DriverCacheState {")
        end = cpp.index("std::vector<u8> PipelineCache::LoadDriverCache()", start)
        source = r'''
#include <atomic>
#include <cassert>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>
#include "video_core/renderer_vulkan/driver_cache_file.h"
using u8 = std::uint8_t;
using u64 = std::uint64_t;
namespace vk { using Device = unsigned; using PipelineCache = unsigned; }
constexpr int VK_SUCCESS = 0, VK_INCOMPLETE = 5;
struct Api {
    std::vector<u8> payload;
    bool incomplete = false;
    int vkGetPipelineCacheData(vk::Device, vk::PipelineCache, size_t* size, void* data) const {
        *size = payload.size();
        if (data) std::memcpy(data, payload.data(), payload.size());
        return data && incomplete ? VK_INCOMPLETE : VK_SUCCESS;
    }
} global_api;
#define VULKAN_HPP_DEFAULT_DISPATCHER global_api
namespace Vulkan {
struct PipelineCache { struct DriverCacheState; };
ACTUAL
}
int main(int argc, char** argv) {
    assert(argc == 2);
    using namespace Vulkan;
    PipelineCache::DriverCacheState state;
    state.directory = argv[1];
    state.identity = {0x5143, 9, 1, {1,2,3}};
    state.caches = {1, 2}; state.saved_hashes.resize(2);
    auto& data = global_api.payload;
    data.resize(64);
    std::uint32_t header[]{32, 1, state.identity.vendor, state.identity.device};
    std::memcpy(data.data(), header, 16);
    std::memcpy(data.data()+16, state.identity.uuid.data(), 16);
    state.Save(0);
    assert(DriverCacheFile::Load(state.File(0), state.identity) == data);
    assert(DriverCacheFile::Load(state.File(1), state.identity) == data);
    const auto previous = data;
    const auto hashes = state.saved_hashes;
    data.back()++; global_api.incomplete = true;
    state.Save(0);
    assert(state.saved_hashes == hashes);
    assert(DriverCacheFile::Load(state.File(0), state.identity) == previous);
    global_api.incomplete = false; state.Save(0);
    assert(DriverCacheFile::Load(state.File(0), state.identity) == data);
    std::atomic<bool> started{}, stopped{};
    state.worker = std::jthread([&](std::stop_token stop) {
        std::mutex mutex;
        std::unique_lock lock{mutex};
        started = true;
        state.wake.wait_for(lock, stop, std::chrono::seconds(30), [] { return false; });
        assert(stop.stop_requested());
        stopped = true;
    });
    while (!started) std::this_thread::yield();
    state.Stop(); assert(stopped && !state.worker.joinable());
    state.Stop();
}
'''.replace("ACTUAL", cpp[start:end])
        with tempfile.TemporaryDirectory() as directory:
            exe = build(source, directory, "checkpoint")
            checked([str(exe), directory])
        destructor = cpp[cpp.index("PipelineCache::~PipelineCache()"):cpp.index("// gow3: shader/pipeline")]
        self.assertLess(destructor.index("StopDriverCache()"), destructor.index("FinishCompilations()"))
        self.assertLess(destructor.index("FinishCompilations()"), destructor.index("SaveDriverCache()"))

    def test_copy_range_union_keeps_holes_and_rejects_overflow(self):
        source = r'''
#include <cassert>
#include "video_core/buffer_cache/copy_ranges.h"
int main() {
    using namespace VideoCore;
    const CopyRange ranges[]{{12288,4},{0,16},{8,24},{4096,8},{0,8},{5000,0}};
    const auto result = NormalizeCopyRanges(16384, ranges);
    assert(result && (*result == std::vector<CopyRange>{{0,32},{4096,8},{12288,4}}));
    const CopyRange overflow[]{{0xfffffff0,32}};
    assert(!NormalizeCopyRanges(0xffffffff, overflow));
    const CopyRange outside[]{{16,1}};
    assert(!NormalizeCopyRanges(16, outside));
    const CopyRange adjacent[]{{0,8},{8,8}};
    assert((NormalizeCopyRanges(16,adjacent) == std::vector<CopyRange>{{0,16}}));
}
'''
        with tempfile.TemporaryDirectory() as directory:
            exe = build(source, directory, "ranges")
            checked([str(exe)])

    def test_range_page_locks_are_unique_and_protection_follows_upload(self):
        header = (INCLUDES / "video_core/buffer_cache/memory_tracker.h").read_text()
        start = header.index("    void ForEachUploadRanges(")
        end = header.index("    /// Call", start)
        source = r'''
#include <cassert>
#include <mutex>
#include <set>
#include <array>
#include "video_core/buffer_cache/copy_ranges.h"
using u64 = std::uint64_t;
using VAddr = u64;
using namespace VideoCore;
enum class Type { CPU, GPU };
struct LockType {
    bool held = false;
    void lock() { assert(!held); held = true; }
    void unlock() { assert(held); held = false; }
};
struct RegionManager {
    LockType lock;
    u64 address{};
    std::set<u64> cpu_pages{0,1,2,3};
    std::vector<CopyRange> gpu;
    u64 GetCpuAddr() const { return address; }
    template<Type, bool> void ForEachModifiedRange(u64 at, u64 size, auto&& func) {
        assert(lock.held);
        for (auto page = (at-address)/4096; page < (at-address+size+4095)/4096; ++page) {
            if (cpu_pages.erase(page)) func(address+page*4096,4096);
        }
    }
    template<Type, bool> void ChangeRegionState(u64 at, u64 size) {
        assert(lock.held); gpu.emplace_back(std::uint32_t(at-address),std::uint32_t(size));
    }
};
class Tracker {
public:
    std::array<RegionManager,2> regions;
    Tracker() { regions[1].address = 65536; }
    template<bool> void IteratePages(u64 at, u64 size, auto&& func) {
        while(size) {
            auto index = at/65536, offset = at%65536, length = std::min(size,65536-offset);
            func(&regions[index], offset, length); size-=length; at+=length;
        }
    }
METHOD
};
int main() {
    Tracker tracker;
    const CopyRange ranges[]{{0,4},{32,4},{8192,4},{65536,4}};
    std::vector<u64> uploaded;
    tracker.ForEachUploadRanges(0,ranges,true,[&](u64 at,u64) { uploaded.push_back(at); },[&] {
        for(auto& region:tracker.regions) { assert(region.lock.held); assert(region.gpu.empty()); }
    });
    assert((uploaded == std::vector<u64>{0,8192,65536}));
    for(auto& region:tracker.regions) assert(!region.lock.held);
    assert(tracker.regions[0].gpu.size()==3 && tracker.regions[1].gpu.size()==1);
    assert(tracker.regions[0].cpu_pages.contains(1));
}
'''.replace("METHOD", header[start:end])
        with tempfile.TemporaryDirectory() as directory:
            exe = build(source, directory, "page_locks")
            checked([str(exe)])


if __name__ == "__main__":
    unittest.main()
