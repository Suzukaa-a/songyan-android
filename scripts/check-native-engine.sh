#!/usr/bin/env bash
# Verify the packaged engine: JNI surface, 16 KB alignment (ELF and APK), the pinned librime
# marker, and that nothing but the JNI entry points is exported (librime and plugin internals
# are hidden so the linker can drop unused code).
# Usage: scripts/check-native-engine.sh <apk-or-build-directory> [--abi ABI] [--llvm-bin DIRECTORY]
set -euo pipefail
script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 - "$script_root" "$@" <<'PY'
import argparse
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

root = Path(sys.argv.pop(1))
parser = argparse.ArgumentParser(description="Verify packaged Songyan native engine libraries.")
parser.add_argument("artifact", type=Path, help="APK or directory containing ABI libraries")
parser.add_argument("--abi", action="append", choices=("arm64-v8a", "x86_64", "armeabi-v7a"),
                    help="Check only this ABI; repeat to check several. Default: all three.")
parser.add_argument("--llvm-bin", type=Path, help="NDK directory containing llvm-objdump and llvm-nm")
args = parser.parse_args()
abis = args.abi or ["arm64-v8a", "x86_64", "armeabi-v7a"]
required = {"JNI_OnLoad"}
# Besides JNI entry points, the build marker is the only symbol allowed in the dynamic table.
allowed_exports = {"JNI_OnLoad", "songyan_librime_marker"}
page_size = 16384
jni_prefix = "Java_app_songyan_ime_rime_RimeNative_"


def find_llvm():
    if args.llvm_bin:
        directories = [args.llvm_bin]
    else:
        directories = []
        for variable in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
            if os.environ.get(variable):
                directories.extend(Path(os.environ[variable]).glob("toolchains/llvm/prebuilt/*/bin"))
        sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
                   or root / ".local/android-sdk")
        directories.extend(sorted(sdk.glob("ndk/*/toolchains/llvm/prebuilt/*/bin"), reverse=True))
        command = shutil.which("llvm-objdump")
        if command:
            directories.append(Path(command).parent)
    for directory in directories:
        if all(os.access(directory / name, os.X_OK) for name in ("llvm-objdump", "llvm-nm")):
            return directory
    raise ValueError("NDK LLVM tools were not found. Set ANDROID_NDK_HOME or pass --llvm-bin.")


def jni_externals():
    """Symbol names for every Kotlin 'external fun' in the engine wrapper.

    A missing JNI implementation only surfaces as an UnsatisfiedLinkError at first use on
    a device, so the packaged library is checked against the Kotlin surface instead.
    """
    source = root / "core/rime/src/main/kotlin/app/songyan/ime/rime/RimeNative.kt"
    if not source.is_file():
        raise ValueError(f"{source}: cannot verify the JNI surface without RimeNative.kt.")
    names = re.findall(r"external fun (native\w+)", source.read_text(encoding="utf-8"))
    if not names:
        raise ValueError(f"{source}: no external native functions found.")
    return {jni_prefix + name for name in names}


def load_segments(llvm, library, label):
    headers = subprocess.check_output([str(llvm / "llvm-objdump"), "-p", str(library)], text=True)
    loads = [line for line in headers.splitlines() if re.search(r"\bLOAD\s+off\b", line)]
    if not loads:
        raise ValueError(f"{label}: no LOAD segments; not a shared library.")
    misaligned = [line for line in loads if not re.search(r"\balign 2\*\*14\s*$", line)]
    if misaligned:
        raise ValueError(f"{label}: LOAD segments must all use 2**14 alignment (16 KB pages).")
    return headers, loads


def check_library(library, label, llvm, pinned_commit, jni_required):
    headers, loads = load_segments(llvm, library, label)
    symbols = subprocess.check_output([str(llvm / "llvm-nm"), "-D", "--defined-only", str(library)], text=True)
    names = {line.split()[-1] for line in symbols.splitlines() if line.split()}
    missing = sorted(required - names)
    if missing:
        raise ValueError(f"{label}: missing exports: {', '.join(missing)}.")
    leaked = sorted(name for name in names
                    if name not in allowed_exports and not name.startswith(jni_prefix))
    if leaked:
        raise ValueError(f"{label}: {len(leaked)} unexpected exported symbols (visibility is not "
                         f"hidden?): {', '.join(leaked[:8])}.")
    missing_jni = sorted(jni_required - names)
    if missing_jni:
        raise ValueError(f"{label}: Kotlin external functions without a JNI implementation: "
                         f"{', '.join(missing_jni)}.")
    if "libc++_shared.so" in headers:
        raise ValueError(f"{label}: libc++ is shared; rebuild with ANDROID_STL=c++_static.")
    marker = re.search(rb"Songyan:librime:([0-9.]+):([0-9a-f]{40})\x00", library.read_bytes())
    if not marker:
        raise ValueError(f"{label}: librime build marker is missing; rebuild the engine.")
    version, commit = (value.decode("ascii") for value in marker.groups())
    release = re.search(r"^# Release ([0-9.]+)\b", (root / "native/VERSIONS.env").read_text(),
                        re.MULTILINE)
    if release and release.group(1) != version:
        raise ValueError(f"{label}: built librime {version} differs from native/VERSIONS.env "
                         f"({release.group(1)}); the Kotlin side expects the latter.")
    if commit != pinned_commit:
        raise ValueError(f"{label}: librime commit {commit} differs from the pinned {pinned_commit}.")
    return (f"{len(loads)} LOAD segments align 2**14; {len(jni_required & names)} JNI entries; "
            f"only JNI exports; librime {version} @ {commit}")


def check_support_library(library, label, llvm):
    _, loads = load_segments(llvm, library, label)
    return f"{len(loads)} LOAD segments align 2**14"


def check_zip_layout(artifact):
    """Every stored (uncompressed) .so must start on a 16 KB boundary.

    With extractNativeLibs=false the loader maps the library straight out of the APK, so a
    stored entry that is not 16 KB aligned cannot be loaded on a 16 KB page-size device.
    Deflated entries are extracted at install time, where only the ELF alignment applies.
    """
    aligned = []
    with zipfile.ZipFile(artifact) as package, open(artifact, "rb") as stream:
        entries = [item for item in package.infolist()
                   if item.filename.startswith("lib/") and item.filename.endswith(".so")]
        if not entries:
            raise ValueError(f"{artifact.name}: no lib/*/*.so entries; is this the packaged app?")
        for entry in entries:
            stream.seek(entry.header_offset)
            header = stream.read(30)
            if len(header) != 30:
                raise ValueError(f"{entry.filename}: truncated local file header.")
            (signature, _version, _flags, method, _time, _date, _crc, _compressed, _size,
             name_length, extra_length) = struct.unpack("<IHHHHHIIIHH", header)
            if signature != 0x04034B50:
                raise ValueError(f"{entry.filename}: bad local file header signature.")
            if method == zipfile.ZIP_STORED:
                data_offset = entry.header_offset + 30 + name_length + extra_length
                if data_offset % page_size:
                    raise ValueError(f"{entry.filename}: stored at offset {data_offset}, not a "
                                     f"multiple of {page_size}; a 16 KB page device cannot map it.")
                aligned.append(entry.filename)
    return aligned


def check_apk(artifact, llvm, pinned_commit, jni_required):
    aligned = check_zip_layout(artifact)
    print(f"PASS {artifact.name}: {len(aligned)} stored .so entries at 16 KB boundaries")
    cache = root / ".local"
    cache.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="native-check-", dir=cache) as temporary:
        directory = Path(temporary)
        with zipfile.ZipFile(artifact) as package:
            for entry in package.infolist():
                if not entry.filename.startswith("lib/") or not entry.filename.endswith(".so"):
                    continue
                parts = Path(entry.filename).parts
                abi = parts[1] if len(parts) > 1 else ""
                engine = entry.filename.endswith("libsongyan_rime.so")
                if engine and abi not in abis:
                    continue
                library = directory / entry.filename.replace("/", "_")
                library.write_bytes(package.read(entry))
                if engine:
                    print(f"PASS {entry.filename}: "
                          f"{check_library(library, entry.filename, llvm, pinned_commit, jni_required)}")
                else:
                    print(f"PASS {entry.filename}: {check_support_library(library, entry.filename, llvm)}")


def main():
    artifact = args.artifact.resolve()
    llvm = find_llvm()
    pins = (root / "native/VERSIONS.env").read_text()
    pinned_commit = re.search(r"^LIBRIME_COMMIT=([0-9a-f]{40})$", pins, re.MULTILINE).group(1)
    jni_required = jni_externals()
    if artifact.is_dir():
        libraries = sorted(artifact.rglob("libsongyan_rime.so"))
        for abi in abis:
            matches = [path for path in libraries if path.parent.name == abi]
            if not matches:
                raise ValueError(f"{artifact}: missing {abi}/libsongyan_rime.so.")
            for library in matches:
                details = check_library(library, str(library.relative_to(artifact)), llvm,
                                        pinned_commit, jni_required)
            print(f"PASS {abi}: {len(matches)} libraries checked; {details}")
    elif artifact.is_file() and zipfile.is_zipfile(artifact):
        check_apk(artifact, llvm, pinned_commit, jni_required)
    else:
        raise ValueError(f"{artifact}: provide an APK or a build directory.")


try:
    main()
except (OSError, ValueError, AttributeError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
    sys.exit(f"Native engine check failed: {error}")
PY
