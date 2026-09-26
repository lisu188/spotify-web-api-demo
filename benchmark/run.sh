#!/usr/bin/env bash
# Run in WSL/Linux with Java 21 on PATH. No provider credentials or accounts are used.
set -eo pipefail
if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then
  echo "Usage: bash benchmark/run.sh BASELINE_DIR OPTIMIZED_DIR OUTPUT_DIR [CPU]" >&2
  exit 2
fi
baseline=$(realpath "$1")
optimized=$(realpath "$2")
output=$(realpath -m "$3")
harness=$(cd "$(dirname "$0")" && pwd)
java_command=$(command -v java)
if [ -n "$JAVA_HOME" ]; then java_command="$JAVA_HOME/bin/java"; fi
cpu="$4"
if [ -z "$cpu" ]; then cpu=$(python3 -c 'import os; print(min(os.sched_getaffinity(0)))'); fi
baseline_revision="$BENCHMARK_BASELINE_REVISION"
optimized_revision="$BENCHMARK_OPTIMIZED_REVISION"
if [ -z "$baseline_revision" ]; then baseline_revision=$(git -C "$baseline" rev-parse HEAD); fi
if [ -z "$optimized_revision" ]; then optimized_revision=$(git -C "$optimized" rev-parse HEAD); fi
if [ "$baseline_revision" != 5369a02692f026d989b527dfa19ba74799382045 ]; then
  echo "Baseline must be unchanged pinned main 5369a02692f026d989b527dfa19ba74799382045" >&2
  exit 2
fi
if [ -e "$output/run.json" ]; then
  echo "Refusing to overwrite an existing benchmark run: $output" >&2
  exit 2
fi
mkdir -p "$output/logs"
python3 - "$output" "$baseline" "$optimized" "$baseline_revision" "$optimized_revision" "$cpu" "$harness" "$java_command" <<'PY'
import hashlib, json, os, pathlib, platform, re, subprocess, sys
output, baseline, optimized, baseline_sha, optimized_sha, cpu, harness, java_command = sys.argv[1:]
java_version = subprocess.check_output([java_command, "-version"], stderr=subprocess.STDOUT, text=True)
if not re.search(r'version "21[.+"]', java_version):
    raise ValueError("Benchmark requires Java 21: " + java_version)
root = pathlib.Path(harness)
files = sorted(p for p in root.rglob("*") if p.is_file() and "__pycache__" not in p.parts)
metadata = {
    "baselineDirectory": baseline, "optimizedDirectory": optimized,
    "baselineRevision": baseline_sha, "optimizedRevision": optimized_sha,
    "cpu": cpu, "loadAverageStart": os.getloadavg(), "host": platform.platform(), "java": java_version,
    "profile": "WSL/Linux one CPU affinity; JVM -Xmx1536m -XX:ActiveProcessorCount=1; no container memory limit; shared host with other builds",
    "trialProtocol": "3 paired trials per scenario; alternating revision order per trial; fresh JVM per revision/trial; each JVM does 1 untimed warmup then 1 measured run; warm scenarios additionally prime caches once",
    "latencyMs": {"spotifySearch": 20, "lastFmPage": 5, "playlistRequest": 5},
    "harnessSha256": {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
}
(pathlib.Path(output) / "run.json").write_text(json.dumps(metadata, indent=2) + "\n")
PY
# Verify actual measured source bytes against commit-tagged git archives.
for variant in baseline optimized; do
  if [ "$variant" = baseline ]; then
    checkout="$baseline"; revision="$baseline_revision"; archive="$BENCHMARK_BASELINE_ARCHIVE"
  else
    checkout="$optimized"; revision="$optimized_revision"; archive="$BENCHMARK_OPTIMIZED_ARCHIVE"
  fi
  if [ -z "$archive" ]; then
    archive="$output/$variant-source.tar"
    git -C "$checkout" archive --format=tar --output="$archive" "$revision"
  fi
  python3 "$harness/verify_sources.py" "$archive" "$checkout" "$revision" "$output/$variant-source-manifest.json"
done
# Compile outside CPU affinity; only measured JVMs need the one-CPU profile.
for variant in baseline optimized; do
  if [ "$variant" = baseline ]; then checkout="$baseline"; else checkout="$optimized"; fi
  echo "Compiling $variant benchmark classes"
  if ! (
    cd "$checkout"
    bash ./gradlew --no-daemon --max-workers=1 \
      -I "$harness/yearly-benchmark.init.gradle" writeYearlyBenchmarkClasspath \
      "-Pbenchmark.harness=$harness"
  ) > "$output/logs/$variant-compile.log" 2>&1; then
    tail -n 100 "$output/logs/$variant-compile.log" >&2
    exit 1
  fi
done
run_one() {
  variant="$1"
  scenario="$2"
  trial="$3"
  if [ "$variant" = baseline ]; then checkout="$baseline"; revision="$baseline_revision"
  else checkout="$optimized"; revision="$optimized_revision"; fi
  log="$output/logs/$variant-$scenario-$trial.log"
  echo "Running $variant $scenario trial $trial on CPU $cpu"
  if ! (
    cd "$checkout"
    taskset -c "$cpu" "$java_command" -Xms256m -Xmx1536m -XX:ActiveProcessorCount=1 \
      -Dapp.state-store.mode=memory -DBASE_URL=http://localhost \
      -DSPOTIFY_CLIENT_ID=synthetic-id -DSPOTIFY_CLIENT_SECRET=synthetic-secret \
      -DLASTFM_API_KEY=synthetic-key -DLASTFM_API_SECRET=synthetic-secret \
      "-Dbenchmark.variant=$variant" "-Dbenchmark.revision=$revision" \
      "-Dbenchmark.scenario=$scenario" "-Dbenchmark.output=$output" \
      -Dbenchmark.trials=1 "-Dbenchmark.trialOffset=$((trial - 1))" \
      -cp "$(cat build/yearly-benchmark-classpath.txt)" \
      com.lis.spotify.service.YearlyPlaylistBenchmark
  ) > "$log" 2>&1; then
    tail -n 100 "$log" >&2
    echo "Benchmark failed; existing raw evidence retained in $output" >&2
    return 1
  fi
}
for fixture in sparse-unique dense-unique sparse-repeated dense-repeated; do
  for cache in cold warm; do
    scenario="$fixture-$cache"
    for trial in 1 2 3; do
      if [ "$((trial % 2))" -eq 1 ]; then variants="baseline optimized"; else variants="optimized baseline"; fi
      for variant in $variants; do run_one "$variant" "$scenario" "$trial"; done
    done
  done
done
python3 - "$output/run.json" <<'PY'
import json, os, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["loadAverageEnd"] = os.getloadavg()
path.write_text(json.dumps(data, indent=2) + "\n")
PY
python3 "$harness/summarize.py" "$output"
