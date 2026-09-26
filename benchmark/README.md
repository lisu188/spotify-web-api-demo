# Yearly generation benchmark

This opt-in harness compiles unchanged against the pinned production baseline
5369a02692f026d989b527dfa19ba74799382045 and the optimized revision. It is not included
in normal tests or coverage. It never connects to Spotify, Last.fm, or an account.

Use Linux/WSL with Java 21, Python 3, and taskset. Build artifacts should live in
Linux storage rather than the Windows drive. Keep production source snapshots at
the exact measured commits; the harness is supplied externally to both snapshots.

~~~sh
export JAVA_HOME=/absolute/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
export BENCHMARK_BASELINE_REVISION=5369a02692f026d989b527dfa19ba74799382045
export BENCHMARK_OPTIMIZED_REVISION=0ae29dd1be1233ca883aeea3586a025eee38447d
export BENCHMARK_BASELINE_ARCHIVE=/absolute/path/baseline-source.tar
export BENCHMARK_OPTIMIZED_ARCHIVE=/absolute/path/optimized-source.tar
bash /absolute/path/benchmark/run.sh \
  "$HOME"/spotify-yearly-benchmark/baseline \
  "$HOME"/spotify-yearly-benchmark/optimized \
  "$HOME"/spotify-yearly-benchmark/results
~~~

Revision overrides support verified source copies without Git metadata. With real
checkouts, omit them to derive HEAD and generate commit-tagged git archives.
For source copies, supply archives made by git archive at the measured commits.
The runner verifies every archived source file before compiling, allows only
text line-ending normalization, and retains byte-hash manifests with the results.
Do not label dirty code as a committed SHA.

The runner alternates baseline/optimized order for three trial pairs per scenario.
Each variant/trial uses a fresh JVM, one untimed warmup, then one measured trial.
Warm-cache scenarios additionally prime the same services before their warmup.
Every process is pinned to one allowed CPU, uses -Xmx1536m, and reports one active
processor. This approximates the service's CPU/heap; it does not impose a 2 GiB
container memory limit. Gradle compiles each revision once and exports its test
classpath; the runner then invokes Java directly for each trial. Startup,
compilation, and fixture setup are untimed. This is a shared host with other
builds; per-trial and overall load averages are recorded without process details.

Four fixture shapes × cold/warm caches = eight scenarios: sparse/dense unique
songs and sparse/dense repeated songs. All retain 2005–2026, four active years,
250 source scrobbles per populated year, provider page ordering, actual matching,
and actual playlist reconciliation. Synthetic latency is 20 ms per search, 5 ms
per Last.fm page, and 5 ms per playlist request. The baseline's existing algorithm
is measured; no artificial all-years-serial implementation is used.

The fake transport models playlist pagination and 100-item add chunks and counts
authentication-header generation. Mocking RestTemplate.exchange bypasses real HTTP
pool acquisition and Spotify response serialization; timings do not measure pool
capacity or attribute speedups to pooling. It does not simulate token expiry, provider
rate limits, Firestore, or network jitter. Assertions cover ordered contents,
skipped empty years, progress, request counts, and optimized bounds.
Failures preserve raw evidence and stop the runner.

run.json records revisions, JVM/profile details, protocol, and harness hashes.
Per-trial JSON and raw.csv preserve measurements. summary.json and report.md show
median/min/max, speedup, requests/duplicates, peak concurrency, CPU, heap, and RSS.
Memory sampling is every 10 ms and includes the mocking/test runtime.

To smoke-test one baseline trial before the full run:

~~~sh
cd "$HOME"/spotify-yearly-benchmark/baseline
taskset -c 0 bash ./gradlew --no-daemon --max-workers=1 \
  -I /absolute/path/benchmark/yearly-benchmark.init.gradle yearlyBenchmark \
  -Pbenchmark.harness=/absolute/path/benchmark \
  -Pbenchmark.variant=baseline -Pbenchmark.scenario=sparse-repeated-cold \
  -Pbenchmark.revision=5369a02692f026d989b527dfa19ba74799382045 \
  -Pbenchmark.trials=1 -Pbenchmark.output="$HOME"/spotify-yearly-benchmark/smoke
~~~

Choose a CPU in the process's allowed affinity if CPU 0 is unavailable. Regenerate
a report from a complete run with: python3 benchmark/summarize.py OUTPUT.
