#!/usr/bin/env python3
"""
Fetch Python + python-pip and ALL their transitive Termux dependencies
as .deb files for a given architecture, then tar them up into a single
bundle suitable for embedding in the APK's assets/.

Why this exists:
  `apt-get download python python-pip` on-device only fetches the
  top-level packages' direct .deb files. The actual install pulls in
  ~30 transitive native deps (libffi, openssl, libsqlite, ncurses,
  libbz2, liblzma, libcrypt, libexpat, readline, ...). Resolving that
  tree on-device requires `apt-get update` + `apt install`, which is
  exactly the slow + flaky step we want to eliminate by pre-bundling.

  This script does the dependency resolution at build time using only
  the Termux apt repo metadata (Packages.gz), so we get the full
  closure of .deb files without ever running apt-get itself.

Usage:
  python3 scripts/fetch-python-bundle.py [--arch ARCH] [--out PATH]

Output:
  A directory containing all .deb files, plus a MANIFEST.txt listing
  them. Caller is expected to `tar -czf` this directory into
  app/src/main/assets/python-bundle.tar.gz.

Notes:
  - Architecture defaults to aarch64 (matches the APK's abiFilters).
  - The Termux apt repo URL is hard-coded; update REPO if it moves.
  - Pure-python; no external deps, so it runs on GitHub's setup-python.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import urllib.request
import gzip


REPO = "https://packages.termux.dev/apt/termux-main"
DIST = "stable"
COMPONENT = "main"

# Root packages whose entire dependency closure we want to bundle.
# `python` is the meta-package; `python-pip` pulls in setuptools + wheel.
ROOT_PACKAGES = ["python", "python-pip"]


def parse_packages(packages_text: str) -> dict[str, dict[str, str]]:
    """Parse a Debian-style Packages file into {name: fields_dict}."""
    pkgs: dict[str, dict[str, str]] = {}
    for block in packages_text.split("\n\n"):
        block = block.strip()
        if not block:
            continue
        pkg: dict[str, str] = {}
        last_key = None
        for line in block.split("\n"):
            if line.startswith((" ", "\t")) and last_key:
                pkg[last_key] += "\n" + line
            elif ":" in line:
                k, _, v = line.partition(":")
                pkg[k.strip()] = v.strip()
                last_key = k.strip()
        name = pkg.get("Package")
        if name:
            pkgs[name] = pkg
    return pkgs


def collect_closure(roots: list[str], pkgs: dict[str, dict[str, str]]) -> set[str]:
    """BFS-collect all package names reachable via Depends/Pre-Depends."""
    to_download: set[str] = set()
    queue = list(roots)
    visited: set[str] = set()
    while queue:
        name = queue.pop(0)
        if name in visited:
            continue
        visited.add(name)
        if name not in pkgs:
            # Virtual package or external dep — skip (apt resolves at install).
            continue
        to_download.add(name)
        deps_str = pkgs[name].get("Depends", "") + "," + pkgs[name].get("Pre-Depends", "")
        for dep in deps_str.split(","):
            dep = dep.strip()
            if not dep:
                continue
            # Strip version constraints: "libffi (>= 3.4)" -> "libffi"
            m = re.match(r"^([a-zA-Z0-9._+-]+)", dep)
            if m:
                queue.append(m.group(1))
    return to_download


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--arch", default="aarch64", help="Target arch (default: aarch64)")
    ap.add_argument("--out", required=True, help="Output directory for .deb files")
    ap.add_argument(
        "--extra",
        action="append",
        default=[],
        help="Extra root packages to include (e.g. --extra libffi)",
    )
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)

    # 1. Download Packages.gz index for the target arch.
    packages_url = f"{REPO}/dists/{DIST}/{COMPONENT}/binary-{args.arch}/Packages.gz"
    print(f"[fetch] {packages_url}")
    with urllib.request.urlopen(packages_url) as r:
        packages_text = gzip.decompress(r.read()).decode("utf-8", errors="replace")
    pkgs = parse_packages(packages_text)
    print(f"[fetch] index has {len(pkgs)} packages for arch={args.arch}")

    # 2. Resolve the full dependency closure.
    roots = list(ROOT_PACKAGES) + list(args.extra)
    to_download = collect_closure(roots, pkgs)
    print(f"[fetch] closure of {roots} = {len(to_download)} packages:")
    for n in sorted(to_download):
        v = pkgs[n].get("Version", "?")
        s = pkgs[n].get("Filename", "?").split("/")[-1]
        print(f"    - {n} ({v}) -> {s}")

    # 3. Download each .deb.
    manifest_lines = []
    for name in sorted(to_download):
        pkg = pkgs[name]
        filename = pkg["Filename"]
        url = f"{REPO}/{filename}"
        basename = os.path.basename(filename)
        out_path = os.path.join(args.out, basename)
        print(f"  [get] {name} -> {basename}")
        subprocess.check_call(
            ["curl", "-fsSL", "--retry", "3", "-o", out_path, url]
        )
        manifest_lines.append(f"{name}\t{pkg.get('Version', '?')}\t{basename}")

    # 4. Write a manifest for debugging / reproducibility.
    manifest_path = os.path.join(args.out, "MANIFEST.txt")
    with open(manifest_path, "w") as f:
        f.write(f"# Python bundle for arch={args.arch}\n")
        f.write(f"# Roots: {roots}\n")
        f.write(f"# Total: {len(to_download)} packages\n")
        f.write("# Format: name\tversion\tfilename\n")
        f.write("\n".join(manifest_lines) + "\n")
    print(f"[fetch] manifest written to {manifest_path}")
    print(f"[fetch] done: {len(to_download)} debs in {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
