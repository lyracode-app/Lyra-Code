#!/usr/bin/env python3
"""Compare APK contents and summarize R8 removals using only Python's stdlib."""

import argparse
from collections import defaultdict
from pathlib import Path
import re
import zipfile


def category(name):
    if re.fullmatch(r"classes\d*\.dex", name):
        return "DEX"
    if name.startswith("lib/"):
        return "/".join(name.split("/")[:2])
    if name.startswith("assets/"):
        return "assets"
    if name.startswith("res/") or name == "resources.arsc":
        return "Android resources"
    return "Other"


def inspect_apk(path):
    totals = defaultdict(lambda: [0, 0])
    with zipfile.ZipFile(path) as apk:
        for entry in apk.infolist():
            values = totals[category(entry.filename)]
            values[0] += entry.compress_size
            values[1] += entry.file_size
    print(f"\n{path}: {path.stat().st_size:,} bytes ({path.stat().st_size / 1024**2:.2f} MiB)")
    print(f"{'Category':<24} {'ZIP bytes':>14} {'Uncompressed':>14}")
    for name, (compressed, raw) in sorted(totals.items()):
        print(f"{name:<24} {compressed:>14,} {raw:>14,}")


def inspect_usage(path):
    classes = fields = methods = 0
    app_classes = []
    owner = ""
    app_fields = app_methods = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        if line[0].isspace():
            if "(" in line:
                methods += 1
                if owner.startswith("com.yukisoffd.lyracode."):
                    app_methods += 1
            else:
                fields += 1
                if owner.startswith("com.yukisoffd.lyracode."):
                    app_fields += 1
        else:
            owner = line.rstrip(":")
            # An owner followed by ':' groups removed members of a retained class.
            if not line.endswith(":"):
                classes += 1
                if owner.startswith("com.yukisoffd.lyracode."):
                    app_classes.append(owner)
    print(f"\nR8 usage: {path}")
    print(f"Removed classes: {classes:,}; field entries: {fields:,}; method entries: {methods:,}")
    print(f"App classes: {len(app_classes):,}; app field entries: {app_fields:,}; app method entries: {app_methods:,}")
    print("Removal can also result from inlining/merging; this is not a source-level unused-symbol proof.")
    for name in app_classes[:25]:
        print(f"  {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="+", type=Path)
    parser.add_argument("--r8-usage", type=Path)
    args = parser.parse_args()
    for path in args.apk:
        inspect_apk(path)
    if len(args.apk) > 1:
        baseline = args.apk[0].stat().st_size
        for path in args.apk[1:]:
            saved = baseline - path.stat().st_size
            print(f"\n{path.name} vs {args.apk[0].name}: {saved:,} bytes saved ({saved / baseline:.1%})")
    if args.r8_usage:
        inspect_usage(args.r8_usage)


if __name__ == "__main__":
    main()
