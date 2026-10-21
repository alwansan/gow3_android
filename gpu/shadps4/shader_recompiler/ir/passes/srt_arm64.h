// SPDX-License-Identifier: GPL-2.0-or-later
// 改编自 gow3_android_daedalus_fork 239b7df (erickmendes22)。
#pragma once
#include <cstdint>
#include <span>
#include <vector>

namespace Shader::Arm64Srt {
// 保留可移植缓存格式；加载时从字节码重新生成 ARM64 指令。
enum SrtOp : std::uint32_t {
    OpEnd, OpPushImm, OpPushDyn, OpPop, OpCopyImm, OpCopyDyn, OpUserData, OpFlat,
    OpImm, OpAdd, OpSub, OpMul, OpShl, OpShr, OpAnd, OpOr, OpXor, OpNot,
    OpUMin, OpUMax, OpBitFieldUExtract,
};
constexpr std::uint32_t OpZeroed = 0x80000000u, SrtMagic = 0x42545253u;
constexpr std::uint32_t MaxDepth = 64, MaxValues = 32;

// x0=用户数据，x1=展平数据，x2=当前指针；x3-x5 为临时寄存器。
// 只使用调用者保存寄存器，栈上依次存放 64 个指针与 32 个值。
inline std::vector<std::uint32_t> Compile(std::span<const std::uint32_t> program) {
    using U32 = std::uint32_t;
    constexpr U32 Frame = MaxDepth * 8 + MaxValues * 4;
    if (program.size() < 2 || program[0] != SrtMagic || program.size() > 262144) return {};
    std::vector<U32> out{0xd10003ffu | (Frame << 10), 0xaa0003e2u};
    std::size_t pc = 1;
    U32 depth = 0, count = 0;
    bool valid = true;
    const auto emit = [&](U32 code) { out.push_back(code); };
    const auto next = [&]() -> U32 {
        if (pc >= program.size()) { valid = false; return 0; }
        return program[pc++];
    };
    const auto imm = [&](U32 reg, U32 value) {
        emit(0x52800000u | ((value & 65535) << 5) | reg);
        if (value >> 16) emit(0x72a00000u | ((value >> 16) << 5) | reg);
    };
    const auto load = [&](U32 reg, U32 slot) {
        emit(0xb94003e0u | (((MaxDepth * 8 + slot * 4) / 4) << 10) | reg);
    };
    const auto store = [&](U32 reg, U32 slot) {
        emit(0xb90003e0u | (((MaxDepth * 8 + slot * 4) / 4) << 10) | reg);
    };
    while (valid && pc < program.size()) {
        const U32 word = next(), op = word & ~OpZeroed;
        const bool zero = (word & OpZeroed) != 0;
        if (op == OpEnd) {
            if (depth || count || pc != program.size()) return {};
            emit(0x910003ffu | (Frame << 10)); emit(0xd65f03c0u);
            return out;
        }
        if (op == OpPushImm || op == OpPushDyn || op == OpCopyImm || op == OpCopyDyn) {
            const bool push = op == OpPushImm || op == OpPushDyn;
            const bool dynamic = op == OpPushDyn || op == OpCopyDyn;
            if (dynamic) {
                if (!count) return {};
                load(4, --count);
                emit(0x0b040084u); emit(0x0b040084u); // 32 位左移两位，保留溢出语义。
            } else {
                imm(4, next() << 2);
            }
            if (push) {
                if (depth == MaxDepth) return {};
                emit(0xf90003e2u | (depth++ << 10));
                emit(zero ? 0xd2800002u : 0xf8646842u);
                if (!zero) emit(0xd340bc42u); // guest 指针截断至 48 位。
            } else {
                emit(zero ? 0x52800003u : 0xb8646843u);
                const U32 offset = next();
                if (offset > 262143) return {};
                imm(5, offset * 4); emit(0xb8256823u);
            }
        } else if (op == OpPop) {
            if (!depth) return {};
            emit(0xf94003e2u | (--depth << 10));
        } else if (op == OpImm || op == OpFlat || op == OpUserData) {
            if (count == MaxValues) return {};
            const U32 value = next();
            if (op == OpImm) imm(3, value);
            else {
                if (value > 262143) return {};
                imm(5, value * 4); emit(0xb8656823u);
            }
            store(3, count++);
        } else if (op == OpNot) {
            if (!count) return {};
            load(3, count - 1); emit(0x2a2303e3u); store(3, count - 1);
        } else if (op == OpBitFieldUExtract) {
            if (count < 3) return {};
            load(5, --count); load(4, --count); load(3, count - 1);
            emit(0x1ac42463u); imm(4, 1); emit(0x1ac52084u);
            emit(0x51000484u); emit(0x0a040063u); store(3, count - 1);
        } else {
            if (count < 2) return {};
            load(4, --count); load(3, count - 1);
            switch (op) {
            case OpAdd: emit(0x0b040063u); break;
            case OpSub: emit(0x4b040063u); break;
            case OpMul: emit(0x1b047c63u); break;
            case OpShl: emit(0x1ac42063u); break;
            case OpShr: emit(0x1ac42463u); break;
            case OpAnd: emit(0x0a040063u); break;
            case OpOr: emit(0x2a040063u); break;
            case OpXor: emit(0x4a040063u); break;
            case OpUMin: emit(0x6b04007fu); emit(0x1a843063u); break;
            case OpUMax: emit(0x6b04007fu); emit(0x1a848063u); break;
            default: return {};
            }
            store(3, count - 1);
        }
    }
    return {};
}
} // namespace Shader::Arm64Srt
