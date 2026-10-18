#!/usr/bin/env python3
"""Summarize raw measured trial files, using only Python's standard library."""
import argparse
import csv
import json
import statistics
from collections import defaultdict
from pathlib import Path


def summary(values):
    return {"median": statistics.median(values), "min": min(values), "max": max(values)}


def summarize(root):
    run = json.loads((root / "run.json").read_text())
    rows = [json.loads(path.read_text()) for path in sorted(root.glob("*/*/trial-*.json"))]
    if not rows:
        raise ValueError("No raw benchmark trials found")
    failed = [row for row in rows if not row["success"]]
    if failed:
        raise ValueError(f"{len(failed)} failed trials; refusing a success report")
    groups = defaultdict(list)
    for row in rows:
        groups[(row["scenario"], row["variant"])].append(row)
    scenarios = [f"{fixture}-{cache}" for fixture in
                 ("sparse-unique", "dense-unique", "sparse-repeated", "dense-repeated")
                 for cache in ("cold", "warm")]
    aggregates = {}
    flat_rows = []
    for row in rows:
        flat = {key: value for key, value in row.items() if not isinstance(value, dict)}
        for section in ("requests", "peakConcurrency", "latencyMs"):
            flat.update({f"{section}.{key}": value for key, value in row[section].items()})
        flat_rows.append(flat)
    headers = sorted({key for row in flat_rows for key in row})
    with (root / "raw.csv").open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=headers)
        writer.writeheader()
        writer.writerows(flat_rows)
    for scenario in scenarios:
        a, b = groups[(scenario, "baseline")], groups[(scenario, "optimized")]
        for variant, group in (("baseline", a), ("optimized", b)):
            if sorted(row["trial"] for row in group) != [1, 2, 3]:
                raise ValueError(f"Expected exactly trials 1,2,3 for {variant} {scenario}")
            if any(row["revision"] != run[f"{variant}Revision"] for row in group):
                raise ValueError(f"Revision mismatch in {variant} {scenario}")
            if any(row["availableProcessors"] != 1 or "," in row["cpuAffinity"] or "-" in row["cpuAffinity"]
                   or not row["cpuAffinity"] for row in group):
                raise ValueError(f"CPU profile not pinned to one CPU for {variant} {scenario}")
        if len({row["playlistFingerprint"] for row in a + b}) != 1:
            raise ValueError(f"Playlist fingerprints differ in {scenario}")
        entry = {}
        for variant, group in (("baseline", a), ("optimized", b)):
            entry[variant] = {
                key: summary([row[key] for row in group])
                for key in ("elapsedMs", "cpuMs", "peakHeapBytes", "peakRssBytes", "peakThreads",
                            "queryRequestCount", "uniqueQueriesRequested", "duplicateQueryRequests",
                            "authHeaderCalls")
            }
            entry[variant]["requests"] = {
                key: summary([row["requests"].get(key, 0) for row in group])
                for key in sorted({key for row in group for key in row["requests"]})
            }
            entry[variant]["peakConcurrency"] = {
                key: max(row["peakConcurrency"].get(key, 0) for row in group)
                for key in sorted({key for row in group for key in row["peakConcurrency"]})
            }
        entry["speedup"] = entry["baseline"]["elapsedMs"]["median"] / entry["optimized"]["elapsedMs"]["median"]
        aggregates[scenario] = entry
    (root / "summary.json").write_text(json.dumps({"run": run, "scenarios": aggregates}, indent=2) + "\n")
    lines = [
        "# Yearly playlist generation benchmark", "",
        f"Baseline: {run['baselineRevision']}. Optimized: {run['optimizedRevision']}.", "",
        run["trialProtocol"] + ".", "",
        "The harness invokes each revision's real yearly, Last.fm, search, REST, and playlist services. "
        "Only authentication and upstream transports are simulated. Search responses contain a wrong first "
        "candidate and an exact second candidate. Existing playlists start empty; actual pagination and "
        "100-item additions are exercised. Every run asserts ordered contents, empty-year preservation, "
        "request counts, and monotonic progress.", "",
        "| Scenario | Baseline median [min–max], ms | Optimized median [min–max], ms | Speedup | Search calls B → O | Duplicate searches B → O | Peak active searches B → O |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]

    def interval(values):
        return f"{values['median']:.1f} [{values['min']:.1f}–{values['max']:.1f}]"

    for scenario, entry in aggregates.items():
        a, b = entry["baseline"], entry["optimized"]
        lines.append(
            f"| {scenario} | {interval(a['elapsedMs'])} | {interval(b['elapsedMs'])} | "
            f"{entry['speedup']:.2f}× | {a['queryRequestCount']['median']:g} → {b['queryRequestCount']['median']:g} | "
            f"{a['duplicateQueryRequests']['median']:g} → {b['duplicateQueryRequests']['median']:g} | "
            f"{a['peakConcurrency'].get('spotify.search', 0)} → {b['peakConcurrency'].get('spotify.search', 0)} |"
        )
    lines += [
        "", "| Scenario | CPU median, ms B → O | Peak heap, MiB B → O | Peak RSS, MiB B → O |",
        "|---|---:|---:|---:|",
    ]
    for scenario, entry in aggregates.items():
        a, b = entry["baseline"], entry["optimized"]
        lines.append(
            f"| {scenario} | {a['cpuMs']['median']:.1f} → {b['cpuMs']['median']:.1f} | "
            f"{a['peakHeapBytes']['max']/1048576:.1f} → {b['peakHeapBytes']['max']/1048576:.1f} | "
            f"{a['peakRssBytes']['max']/1048576:.1f} → {b['peakRssBytes']['max']/1048576:.1f} |"
        )
    lines += [
        "", "## Reproduction and limits", "",
        "- Fixed range: 2005–2026. Sparse fixtures populate only 2026; dense fixtures populate all 22 years. "
        "Each populated year has 250 scrobbles. Unique fixtures use distinct songs; sparse repeated uses "
        "25 songs × 10 listens, dense repeated uses the same 50 songs × 5 listens in every year.",
        "- Synthetic latency: 20 ms/search, 5 ms/Last.fm page, 5 ms/playlist request. "
        "These delays are inputs, not measurements of Spotify or Last.fm.",
        "- Cold trials use fresh in-memory stores. Warm trials prime the same services; playlist state "
        "is reset before measurement, so reconciliation still runs. Priming and setup are untimed.",
        "- " + run["profile"] + ". RSS and heap are sampled every 10 ms and include the test/mocking runtime. "
        "CPU time also includes sampling overhead. Measurements approximate the deployed CPU/heap, "
        "not Cloud Run container parity.",
        "- Mocked RestTemplate.exchange bypasses real HTTP connection-pool acquisition and Spotify "
        "response serialization. Timings do not measure pool capacity or attribute speedups to pooling.",
        "- The host is shared with other builds. Per-trial start/end load averages are preserved; "
        "the run does not claim an isolated machine.",
        "- No real accounts, external HTTP, Firestore, OAuth refresh, network jitter, provider rate limits, "
        "or cloud cold starts are measured. No wall-clock threshold is an automated acceptance gate.",
        "- Raw per-trial JSON, flattened raw.csv, aggregate summary.json, command logs, "
        "and environment/harness hashes in run.json are retained alongside this report.",
    ]
    (root / "report.md").write_text("\n".join(lines) + "\n")
    print(root / "report.md")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    summarize(args.output.resolve())
