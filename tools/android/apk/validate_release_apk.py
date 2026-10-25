#!/usr/bin/env python3
"""Validate the signed, game-free Bionic Android release APK.

Bionic counterpart of the retired glibc validator. The differences that matter:
the runtime payload carries the Bionic runtime tree (no rootfs), the APK ships
libgow3prepare.so/libgow3present.so instead of the retired fexdroid bridges, and
the application id is com.alwansan.gow3.android.
"""
import argparse
import hashlib
import json
import re
import struct
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path

APPLICATION_ID = 'com.alwansan.gow3.android'
JAVA_PACKAGE = 'com/gow3/android/game'
REQUIRED_LIBS = ('lib/arm64-v8a/libgow3prepare.so', 'lib/arm64-v8a/libgow3present.so')
FORBIDDEN_LIBS = ('lib/arm64-v8a/libfxdisplay.so', 'lib/arm64-v8a/libfxio.so')
GAME_CONTENT = ('eboot.bin', 'sce_module', 'sce_sys', 'boot-linked.bin')


class ApkError(ValueError):
    pass


def _badging(apk, aapt):
    if not aapt:
        return ''
    result = subprocess.run([aapt, 'dump', 'badging', str(apk)],
                            check=False, capture_output=True, text=True)
    if result.returncode:
        raise ApkError('metadata: aapt dump badging failed: %s' % result.stderr.strip())
    return result.stdout


def _is_android_elf(data):
    """A Bionic ELF: ELF64 little-endian, and its interpreter is the Android linker."""
    if len(data) < 64 or data[:4] != b'\x7fELF' or data[4] != 2 or data[5] != 1:
        return False
    (e_phoff,) = struct.unpack_from('<Q', data, 32)
    e_phentsize, e_phnum = struct.unpack_from('<HH', data, 54)
    for i in range(e_phnum):
        at = e_phoff + i * e_phentsize
        if at + 56 > len(data):
            return False
        p_type, = struct.unpack_from('<I', data, at)
        if p_type != 3:  # PT_INTERP
            continue
        p_offset, = struct.unpack_from('<Q', data, at + 8)
        p_filesz, = struct.unpack_from('<Q', data, at + 32)
        interpreter = data[p_offset:p_offset + p_filesz].split(b'\0', 1)[0]
        return interpreter == b'/system/bin/linker64'
    return False


def validate_payload_tree(archive_bytes, manifest_bytes):
    """The payload must be the Bionic runtime its manifest declares: every required
    path present, the loader a Bionic ELF, and no game content anywhere."""
    manifest = json.loads(manifest_bytes.decode('utf-8'))
    if manifest.get('application_id') != APPLICATION_ID:
        raise ApkError('archive: payload manifest is built for %s, not %s'
                       % (manifest.get('application_id'), APPLICATION_ID))
    required = [item['path'] for item in manifest.get('required_paths', [])]
    if not required:
        raise ApkError('archive: payload manifest declares no required paths')
    with tempfile.SpooledTemporaryFile() as spool:
        spool.write(archive_bytes)
        spool.seek(0)
        with tarfile.open(fileobj=spool, mode='r:gz') as tar:
            members = {}
            for member in tar:
                if not member.isfile():
                    continue
                members[member.name] = member.size
                if any(fragment in member.name for fragment in GAME_CONTENT):
                    raise ApkError('archive: forbidden game content: %s' % member.name)
                if member.name.endswith('gow3-probe'):
                    data = tar.extractfile(member).read(1024 * 1024)
                    if not _is_android_elf(data):
                        raise ApkError('interpreter: %s is not a Bionic ELF '
                                       '(expected /system/bin/linker64)' % member.name)
            for path in required:
                if path not in members:
                    raise ApkError('archive completeness: payload is missing %s' % path)
    return manifest, members


def validate_apk(apk, aapt=None, allow_debuggable=False, manifest=None):
    apk = Path(apk)
    with zipfile.ZipFile(apk) as package:
        names = set(package.namelist())
        for name in ('assets/runtime.payload', 'assets/runtime.sha256'):
            if name not in names:
                raise ApkError('package: missing %s' % name)
        for name in REQUIRED_LIBS:
            if name not in names:
                raise ApkError('package: missing %s' % name)
        for name in FORBIDDEN_LIBS:
            if name in names:
                raise ApkError('package: retired fexdroid bridge is back: %s' % name)
        if any(fragment in name for name in names for fragment in GAME_CONTENT):
            raise ApkError('package: forbidden game content')
        payload = package.read('assets/runtime.payload')
        declared = package.read('assets/runtime.sha256').decode('ascii').strip()
        actual = hashlib.sha256(payload).hexdigest()
        if declared != actual:
            raise ApkError('checksum: APK payload digest mismatch')
        dex = b''.join(package.read(name) for name in sorted(names)
                       if name.startswith('classes') and name.endswith('.dex'))
        if JAVA_PACKAGE.encode() not in dex:
            raise ApkError('metadata: expected Java package is absent from DEX')
        declared_manifest = json.loads(manifest.read_bytes()) if manifest else None
        if declared_manifest and declared_manifest.get('application_id') != APPLICATION_ID:
            raise ApkError('metadata: --manifest is not a %s manifest' % APPLICATION_ID)
        validate_payload_tree(payload,
                              manifest.read_bytes() if manifest else b'{}')

    badging = _badging(apk, aapt)
    if badging:
        package_match = re.search(r"package: name='([^']+)'", badging)
        if not package_match or package_match.group(1) != APPLICATION_ID:
            raise ApkError('metadata: application id is not %s' % APPLICATION_ID)
        if 'application-debuggable' in badging and not allow_debuggable:
            raise ApkError('metadata: release APK is debuggable')
    return {'apk': str(apk), 'payload_sha256': actual, 'application_id': APPLICATION_ID}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--aapt')
    parser.add_argument('--allow-debuggable', action='store_true')
    parser.add_argument('--manifest', type=Path,
                        help='the repo copy of runtime-manifest.bionic.json, to cross-check')
    args = parser.parse_args(argv)
    try:
        print(validate_apk(args.apk, args.aapt, args.allow_debuggable, args.manifest))
    except (OSError, zipfile.BadZipFile, ApkError) as error:
        parser.error(str(error))


if __name__ == '__main__':
    main()
