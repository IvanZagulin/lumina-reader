#!/usr/bin/env python3
"""Builds the AltStore source (source.json) for a new iPhone build.

The static fields come from source-template.json ("{repository}" is replaced
by the owner/name of the GitHub repository). The version history is read from
the currently published source.json, the new build is put first, older builds
are kept up to --keep entries, and the result is written to --out.

AltStore requires that "version" and "buildVersion" equal the IPA's
CFBundleShortVersionString and CFBundleVersion, and that "bundleIdentifier"
equals its CFBundleIdentifier. ios-release.yml passes exactly the values it
builds the app with. Both the current "versions" list and the legacy
app-level fields (version, versionDate, downloadURL, ...) are written, so old
and new AltStore releases read the same data.

Used by .github/workflows/ios-release.yml; tests: test_update_source.py.
"""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

VERSION_RE = re.compile(r"^\d+(\.\d+){1,3}$")
BUILD_RE = re.compile(r"^\d+$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
REPOSITORY_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


class SourceError(ValueError):
    """Invalid input; the message says which argument."""


def substitute(value: Any, repository: str) -> Any:
    """Replaces "{repository}" in every string of a JSON value."""
    if isinstance(value, str):
        return value.replace("{repository}", repository)
    if isinstance(value, list):
        return [substitute(item, repository) for item in value]
    if isinstance(value, dict):
        return {key: substitute(item, repository) for key, item in value.items()}
    return value


def load_json(path: Path) -> Any:
    """Parsed JSON, or None when the file is missing, empty or not JSON."""
    try:
        text = path.read_text(encoding="utf-8")
    except (FileNotFoundError, IsADirectoryError):
        return None
    if not text.strip():
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return None


def build_number(entry: dict[str, Any]) -> int:
    try:
        return int(str(entry.get("buildVersion", "0")))
    except ValueError:
        return 0


def previous_versions(current: Any, bundle_id: str) -> list[dict[str, Any]]:
    """Valid version entries of bundle_id in the published source, if any."""
    if not isinstance(current, dict) or not isinstance(current.get("apps"), list):
        return []
    for app in current["apps"]:
        if isinstance(app, dict) and app.get("bundleIdentifier") == bundle_id:
            versions = app.get("versions")
            if not isinstance(versions, list):
                return []
            return [
                entry
                for entry in versions
                if isinstance(entry, dict)
                and isinstance(entry.get("version"), str)
                and isinstance(entry.get("downloadURL"), str)
            ]
    return []


def validate(args: argparse.Namespace) -> None:
    if not VERSION_RE.match(args.version):
        raise SourceError(f"--version must look like 1.0.42, got {args.version!r}")
    if not BUILD_RE.match(args.build):
        raise SourceError(f"--build must be a positive integer, got {args.build!r}")
    if not args.url.startswith("https://"):
        raise SourceError(f"--url must be an https URL, got {args.url!r}")
    if args.size <= 0:
        raise SourceError(f"--size must be positive, got {args.size}")
    if not SHA256_RE.match(args.sha256):
        raise SourceError("--sha256 must be 64 lowercase hex characters")
    if not REPOSITORY_RE.match(args.repository):
        raise SourceError(f"--repository must be owner/name, got {args.repository!r}")
    try:
        datetime.fromisoformat(args.date.replace("Z", "+00:00"))
    except ValueError as error:
        raise SourceError(f"--date must be ISO 8601, got {args.date!r}") from error
    if args.keep < 1:
        raise SourceError("--keep must be at least 1")


def build_source(template: dict[str, Any], current: Any, args: argparse.Namespace) -> dict[str, Any]:
    source = substitute(copy.deepcopy(template), args.repository)
    apps = source.get("apps")
    if not isinstance(apps, list) or not apps or not isinstance(apps[0], dict):
        raise SourceError("the template must contain at least one app")
    app = apps[0]
    bundle_id = app.get("bundleIdentifier")
    if not bundle_id:
        raise SourceError("the template's first app has no bundleIdentifier")

    new_entry = {
        "version": args.version,
        "buildVersion": args.build,
        "date": args.date,
        "localizedDescription": args.notes,
        "downloadURL": args.url,
        "size": args.size,
        "sha256": args.sha256,
        "minOSVersion": args.min_os,
    }
    older = [
        entry
        for entry in previous_versions(current, bundle_id)
        if entry.get("version") != args.version and str(entry.get("buildVersion")) != args.build
    ]
    versions = sorted([new_entry, *older], key=build_number, reverse=True)[: args.keep]
    newest = versions[0]

    app["versions"] = versions
    # Legacy fields (AltStore 1.x and some forks read only these).
    app["version"] = newest["version"]
    app["versionDate"] = newest["date"]
    app["versionDescription"] = newest["localizedDescription"]
    app["downloadURL"] = newest["downloadURL"]
    app["size"] = newest["size"]
    return source


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--template", type=Path, required=True)
    parser.add_argument("--current", type=Path, required=True, help="published source.json; may be missing or {}")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--repository", required=True, help="GitHub owner/name, e.g. IvanZagulin/lumina-reader")
    parser.add_argument("--version", required=True, help="CFBundleShortVersionString, e.g. 1.0.42")
    parser.add_argument("--build", required=True, help="CFBundleVersion, e.g. 42")
    parser.add_argument("--url", required=True, help="download URL of the .ipa")
    parser.add_argument("--size", type=int, required=True, help="size of the .ipa in bytes")
    parser.add_argument("--sha256", required=True, help="SHA-256 of the .ipa (hex)")
    parser.add_argument("--date", required=True, help="ISO 8601 build date")
    parser.add_argument("--notes", default="Предварительная сборка для iPhone.")
    parser.add_argument("--min-os", default="15.0", help="minimum iOS version")
    parser.add_argument("--keep", type=int, default=5, help="number of versions to keep")
    args = parser.parse_args(argv)
    args.sha256 = args.sha256.strip().lower()
    return args


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    try:
        validate(args)
        template = load_json(args.template)
        if not isinstance(template, dict):
            raise SourceError(f"cannot read the template {args.template}")
        source = build_source(template, load_json(args.current), args)
    except SourceError as error:
        print(f"update_source.py: {error}", file=sys.stderr)
        return 2
    args.out.write_text(json.dumps(source, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    versions = [entry["version"] for entry in source["apps"][0]["versions"]]
    print(f"wrote {args.out}: {', '.join(versions)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
