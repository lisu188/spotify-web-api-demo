#!/usr/bin/env python3
"""Verify a source copy against a git archive before labeling benchmark results."""
import argparse
import hashlib
import json
import tarfile
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify(archive, source, revision, output):
    source = source.resolve()
    records = {}
    with tarfile.open(archive, "r") as snapshot:
        if snapshot.pax_headers.get("comment") != revision:
            raise ValueError("Archive commit identity does not match the requested revision")
        for member in snapshot.getmembers():
            if member.isdir():
                continue
            relative = Path(member.name)
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError(f"Unsafe archive path: {member.name}")
            path = source / relative
            if member.issym():
                if not path.is_symlink() or str(path.readlink()) != member.linkname:
                    raise ValueError(f"Source symlink differs: {member.name}")
                records[member.name] = {"symlink": member.linkname}
                continue
            if not member.isfile():
                raise ValueError(f"Unsupported archive entry: {member.name}")
            expected = snapshot.extractfile(member).read()
            actual = path.read_bytes()
            normalized = False
            if actual != expected:
                # Git's Windows checkout can translate text line endings. Never normalize binaries.
                try:
                    expected.decode("utf-8")
                    actual.decode("utf-8")
                    normalized = (
                        b"\0" not in expected
                        and b"\0" not in actual
                        and expected.replace(b"\r\n", b"\n") == actual.replace(b"\r\n", b"\n")
                    )
                except UnicodeDecodeError:
                    pass
                if not normalized:
                    raise ValueError(f"Source contents differ from pinned commit: {member.name}")
            records[member.name] = {
                "gitArchiveSha256": digest(expected),
                "measuredFileSha256": digest(actual),
                "textLineEndingsNormalized": normalized,
            }
    # Extra compiler inputs must not silently change the code being benchmarked.
    expected_inputs = {
        name for name in records
        if name.startswith("src/") or name.startswith("buildSrc/")
    }
    actual_inputs = {
        str(path.relative_to(source)).replace("\\", "/")
        for directory in ("src", "buildSrc")
        if (source / directory).exists()
        for path in (source / directory).rglob("*")
        if path.is_file() or path.is_symlink()
    }
    if actual_inputs != expected_inputs:
        raise ValueError(f"Unexpected or missing source inputs: {actual_inputs ^ expected_inputs}")
    result = {
        "revision": revision,
        "archiveSha256": digest(archive.read_bytes()),
        "verification": "All tracked files match git archive, allowing UTF-8 text CRLF checkout conversion; no extra src/buildSrc inputs",
        "files": records,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n")
    print(f"Verified {revision}: {len(records)} tracked files")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("source", type=Path)
    parser.add_argument("revision")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    verify(args.archive, args.source, args.revision, args.output)