#!/usr/bin/env python3
"""Check JSch reflection targets and WorkManager's Room constructor in an APK.

Compare an unshrunk APK with the optimized APK, using the JSch sources JAR
that matches their dependency version. Requires only Python's standard library.
"""

import argparse
import json
from pathlib import Path
import re
import struct
import zipfile


ROOM_DATABASE = "androidx.work.impl.WorkDatabase_Impl"


def read_constructors(apk):
    """Map relevant DEX class definitions to their no-arg constructor flags."""
    result = {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            dex = archive.read(name)
            if not dex.startswith(b"dex\n"):
                raise ValueError(f"Unsupported DEX header: {name}")

            def uint32(offset):
                return struct.unpack_from("<I", dex, offset)[0]

            def uleb128(offset):
                value = shift = 0
                while True:
                    byte = dex[offset]
                    offset += 1
                    value |= (byte & 127) << shift
                    if byte < 128:
                        return value, offset
                    shift += 7

            strings, types = uint32(60), uint32(68)
            prototypes, methods = uint32(76), uint32(92)

            def string(index):
                offset = uint32(strings + index * 4)
                _, offset = uleb128(offset)
                # All target class names and <init> are ASCII in DEX MUTF-8.
                return dex[offset:dex.index(0, offset)].decode("utf-8", errors="replace")

            for index in range(uint32(96)):
                definition = uint32(100) + index * 32
                descriptor = string(uint32(types + uint32(definition) * 4))
                class_name = descriptor[1:-1].replace("/", ".")
                if not (class_name.startswith("com.jcraft.jsch.") or class_name == ROOM_DATABASE):
                    continue
                data = uint32(definition + 24)
                constructors = []
                if data:
                    static_fields, data = uleb128(data)
                    instance_fields, data = uleb128(data)
                    direct_methods, data = uleb128(data)
                    virtual_methods, data = uleb128(data)
                    for _ in range(static_fields + instance_fields):
                        _, data = uleb128(data)  # field index delta
                        _, data = uleb128(data)  # access flags
                    for count in (direct_methods, virtual_methods):
                        method_index = 0
                        for _ in range(count):
                            delta, data = uleb128(data)
                            access, data = uleb128(data)
                            _, data = uleb128(data)  # code offset
                            method_index += delta
                            _, prototype, name_index = struct.unpack_from(
                                "<HHI", dex, methods + method_index * 8,
                            )
                            parameters = uint32(prototypes + prototype * 12 + 8)
                            if string(name_index) == "<init>" and parameters == 0:
                                constructors.append(access)
                result[class_name] = constructors
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path, help="Unshrunk APK with the same JSch version")
    parser.add_argument("optimized", type=Path)
    parser.add_argument("--jsch-sources", required=True, type=Path, help="Matching JSch sources JAR")
    args = parser.parse_args()
    with zipfile.ZipFile(args.jsch_sources) as sources:
        source = sources.read("com/jcraft/jsch/JSch.java").decode("utf-8")
    configured = set(re.findall(r'"(com\.jcraft\.jsch\.[A-Za-z0-9_.$]+)"', source))
    baseline = read_constructors(args.baseline)
    optimized = read_constructors(args.optimized)
    expected = configured & baseline.keys()
    if not expected:
        parser.error("No configured JSch implementations found in the baseline APK")
    missing = sorted(expected - optimized.keys())
    missing_constructors = sorted(
        name for name in expected & optimized.keys()
        if baseline[name] and not optimized[name]
    )
    # Room's constructor is invoked from a different package and must be public.
    room_ok = ROOM_DATABASE not in baseline or any(
        flags & 1 for flags in optimized.get(ROOM_DATABASE, [])
    )
    report = {
        "configured_jsch_classes": len(configured),
        "jsch_classes_in_baseline": len(expected),
        "missing_jsch_classes": missing,
        "missing_jsch_noarg_constructors": missing_constructors,
        "workmanager_database_public_noarg_constructor": room_ok,
    }
    print(json.dumps(report, indent=2))
    return 1 if missing or missing_constructors or not room_ok else 0


if __name__ == "__main__":
    raise SystemExit(main())
