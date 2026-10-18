#!/usr/bin/env python3
"""Verify retained measurements without presenting them as a new benchmark run."""
import argparse
import hashlib
import importlib.util
import json
import shutil
import stat
import tarfile
import zipfile
from pathlib import Path, PurePosixPath


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_relative(name):
    path = PurePosixPath(name)
    require(not path.is_absolute() and ".." not in path.parts and "\\" not in name,
            f"Unsafe archive path: {name}")
    return path


def verify(root, harness, baseline, optimized):
    run = json.loads((root / "run.json").read_text(encoding="utf-8"))
    require(run["baselineRevision"] == baseline and run["optimizedRevision"] == optimized,
            "Retained revisions differ from the requested comparison")
    for variant, revision in (("baseline", baseline), ("optimized", optimized)):
        manifest = json.loads((root / f"{variant}-source-manifest.json").read_text(encoding="utf-8"))
        archive = root / f"{variant}-source.tar"
        require(manifest["revision"] == revision, "Source manifest revision differs")
        require(digest(archive.read_bytes()) == manifest["archiveSha256"], "Source archive hash differs")
        with tarfile.open(archive) as snapshot:
            require(snapshot.pax_headers.get("comment") == revision, "Source archive identity differs")
            members = {}
            for member in snapshot.getmembers():
                safe_relative(member.name)
                if member.isdir():
                    continue
                require(member.isfile() or member.issym(), "Unsupported source archive entry")
                require(member.name not in members, "Duplicate source archive entry")
                members[member.name] = member
                record = manifest["files"][member.name]
                if member.issym():
                    target = safe_relative(member.linkname)
                    require(not target.is_absolute(), "Unsafe source symlink")
                    require(record.get("symlink") == member.linkname, "Source symlink differs")
                else:
                    require(digest(snapshot.extractfile(member).read()) == record["gitArchiveSha256"],
                            "Source member hash differs: " + member.name)
            require(set(members) == set(manifest["files"]), "Source manifest inventory differs")
    source_inputs = {str(p.relative_to(harness)).replace("\\", "/")
                     for p in (harness / "src").rglob("*.kt")}
    expected_inputs = {p for p in run["harnessSha256"] if p.startswith("src/")}
    require(source_inputs == expected_inputs, "Executable harness source inventory differs")
    executable = expected_inputs | {"run.sh", "summarize.py", "verify_sources.py", "yearly-benchmark.init.gradle"}
    for name in executable:
        actual = (harness / name).read_bytes()
        require(digest(actual) == run["harnessSha256"][name]
                or digest(actual.replace(b"\r\n", b"\n")) == run["harnessSha256"][name],
                "Executable harness hash differs: " + name)
    paths = sorted(root.glob("*/*/trial-*.json"))
    rows = [json.loads(p.read_text(encoding="utf-8")) for p in paths]
    scenarios = {f"{fixture}-{cache}" for fixture in
                 ("sparse-unique", "dense-unique", "sparse-repeated", "dense-repeated")
                 for cache in ("cold", "warm")}
    expected = {(variant, scenario, trial) for variant in ("baseline", "optimized")
                for scenario in scenarios for trial in (1, 2, 3)}
    require(len(rows) == 48 and {(r["variant"], r["scenario"], r["trial"]) for r in rows} == expected,
            "Expected exactly all 48 measured trials")
    for row in rows:
        require(row["success"], "Retained trial failed")
        require(row["revision"] == run[row["variant"] + "Revision"], "Trial revision differs")
        require(row["availableProcessors"] == 1 and row["cpuAffinity"].isdigit(), "Trial CPU profile differs")
        require(0 < row["maxHeapBytes"] <= 1536 * 1048576, "Trial heap profile differs")
        if row["variant"] == "optimized":
            require(row["peakConcurrency"].get("spotify.search", 0) <= 8, "Search concurrency exceeds eight")
            require(row["duplicateQueryRequests"] == 0, "Optimized duplicate query requests")
    recomputed = root / "recomputed"
    recomputed.mkdir()
    shutil.copy2(root / "run.json", recomputed / "run.json")
    for path in paths:
        target = recomputed / path.relative_to(root)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    spec = importlib.util.spec_from_file_location("benchmark_summary", harness / "summarize.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.summarize(recomputed)
    require(json.loads((root / "summary.json").read_text(encoding="utf-8")) ==
            json.loads((recomputed / "summary.json").read_text(encoding="utf-8")), "Summary differs from raw trials")
    for name in ("raw.csv", "report.md"):
        require((root / name).read_text(encoding="utf-8") ==
                (recomputed / name).read_text(encoding="utf-8"), name + " differs from raw trials")
    print("Verified retained 48-trial measurements, source identities, executable harness and recomputed results")


def restore(archive, output):
    output.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(archive) as snapshot:
        names = set()
        for member in snapshot.infolist():
            path = safe_relative(member.filename)
            require(member.filename not in names, "Duplicate ZIP entry")
            names.add(member.filename)
            require(not stat.S_ISLNK(member.external_attr >> 16), "ZIP symlinks are not supported")
            target = output.joinpath(*path.parts)
            require(target.resolve().is_relative_to(output.resolve()), "ZIP path escapes output")
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open("xb") as handle:
                    handle.write(snapshot.read(member))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("baseline")
    parser.add_argument("optimized")
    args = parser.parse_args()
    restore(args.archive, args.output)
    verify(args.output, Path(__file__).resolve().parent, args.baseline, args.optimized)
